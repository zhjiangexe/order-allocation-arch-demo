package com.flowzati.archone.stock.infrastructure.configuration;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * app 層重試設定：{@link com.flowzati.archone.stock.infrastructure.retry.SpringAllocationRetryExecutor}
 * 用的 {@link RetryOperations}。跟
 * container 層的
 * {@link com.flowzati.archone.bootstrap.messaging.OrderPromisingKafkaFailurePolicyConfiguration}
 * 是同一套重試策略的兩層，
 * 各自對應不同 Spring 子系統（這裡是純 Java 的 {@code core.retry}，跟 Kafka 無關；那邊是
 * Kafka listener container 層），分工見兩邊 Javadoc。
 */
@Configuration
public class AllocationRetryConfiguration {

  @Bean
  RetryOperations allocationRetryOperations(RetryListener allocationRetryMetricsListener) {
    RetryPolicy retryPolicy = RetryPolicy.builder()
        .includes(OptimisticLockingFailureException.class)
        .maxRetries(2)
        .delay(Duration.ofMillis(100))
        .build();
    RetryTemplate retryTemplate = new RetryTemplate(retryPolicy);
    retryTemplate.setRetryListener(allocationRetryMetricsListener);
    return retryTemplate;
  }
}
