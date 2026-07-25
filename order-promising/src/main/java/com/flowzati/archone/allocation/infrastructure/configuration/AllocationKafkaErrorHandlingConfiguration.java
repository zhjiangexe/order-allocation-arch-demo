package com.flowzati.archone.allocation.infrastructure.configuration;

import com.flowzati.archone.allocation.application.retry.AllocationConcurrencyExhaustedException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 套用到這個服務所有的 {@code @KafkaListener}（Spring Boot 自動抓單一 {@link CommonErrorHandler}
 * bean 接上 listener container factory）——目前只有 {@code AllocationKafkaIntegrationEventConsumer}。
 *
 * <p>{@code AllocationConcurrencyExhaustedException} 代表 app 層重試（見
 * {@link AllocationRetryConfiguration}，3 次）已經撞底，是持續性樂觀鎖衝突，不是真正的錯誤。
 * 若用 Spring Boot 預設處理（立即重送 9 次、無間隔）只會撞回同樣的衝突，所以這裡改成只對這個
 * 例外做 4 次指數退避重送（1s→2s→4s→8s），其餘例外一律直接丟 DLT，不浪費重試額度
 * （見下面 {@code defaultFalse()}）。
 *
 * <p>退避期間會 block 住該 partition 的 consumer thread，但只用來擋 app 層都解不掉的持續衝突
 * ——實測秒級可消退，遠低於 {@code max.poll.interval.ms} 預設 5 分鐘。若這個 topic 未來混進
 * 不相關流量，或衝突需要的等待拉長到分鐘級，這個取捨要重新評估。
 *
 * <p>退避全部用盡後，事件會進 DLT（預設命名 {@code <topic>-dlt}），不會被靜默丟棄。實測驗證過
 * （用 DB trigger 刻意延遲撐爆退避）：114 次 app 層用盡中，82 次被這裡的退避救回，32 次真的落
 * DLT——這 32 筆 Order 仍停在 {@code PENDING}，但可觀測、可重放，跟舊版預設處理「靜默丟棄、
 * 查無此訊息」有本質差異。
 *
 * <p>這也是這個服務唯一直接寫 Kafka 的地方——其餘事件都走 Outbox（DB 寫入 + Debezium CDC），
 * 刻意不讓 app 直接碰 Kafka producer。{@code spring.kafka.producer.key/value-serializer} 這兩個
 * 設定就是為了讓這條例外路徑（Spring Boot 從 {@link KafkaOperations} 自動組出來的 producer）
 * 序列化格式跟這個服務 consumer 端一致。
 */
@Configuration
public class AllocationKafkaErrorHandlingConfiguration {

  @Bean
  CommonErrorHandler allocationKafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);

    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
    backOff.setInitialInterval(1000);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(10_000);

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
    errorHandler.defaultFalse();
    errorHandler.addRetryableExceptions(AllocationConcurrencyExhaustedException.class);
    return errorHandler;
  }
}
