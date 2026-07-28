package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxDelivery;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderingDomainEventTranslator {

  private static final String SKU_STRATEGY = "sku";
  private static final Logger log = LoggerFactory.getLogger(OrderingDomainEventTranslator.class);

  private final OutboxAppender outboxAppender;
  private final String partitionKeyStrategy;

  public OrderingDomainEventTranslator(
      OutboxAppender outboxAppender,
      @Value("${archone.allocation.partition-key-strategy:order-id}") String partitionKeyStrategy
  ) {
    this.outboxAppender = outboxAppender;
    this.partitionKeyStrategy = partitionKeyStrategy;
    log.info("archone.allocation.partition-key-strategy={}", partitionKeyStrategy);
  }

  @EventListener
  public void translate(OrderPlaced event) {
    // integration event 的契約仍是單一 SKU 與數量,尚未改為攜帶行清單,因此在此摺成一行。
    // 摺疊一律經過具名的 requireSingleSku,而不是各自從 lines() 挖。
    String skuCode = LineSnapshot.requireSingleSku(event.lines());
    outboxAppender.append(
        new OrderPlacedIntegrationEvent(
            IdGenerator.nextId(), event.orderId(), skuCode,
            LineSnapshot.totalQuantity(event.lines()), event.placedAt()),
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        new OutboxDelivery(
            OrderingEventTopics.ORDER_EVENTS,
            partitionKey(event.orderId(), () -> skuCode)),
        event.placedAt()
    );
  }

  @EventListener
  public void translate(OrderCancelled event) {
    outboxAppender.append(
        new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt()),
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        new OutboxDelivery(
            OrderingEventTopics.ORDER_EVENTS,
            partitionKey(
                event.orderId(), () -> LineSnapshot.requireSingleSku(event.lines()))),
        event.cancelledAt()
    );
  }

  /**
   * sku 策略下用 SKU 當 partition key，讓同一個 SKU 的事件全部收斂進同一個
   * partition（single-writer，見 docs/superpowers/specs/2026-07-26-v3-single-writer-design.md）；
   * 預設（或任何非 "sku" 的值）沿用 v1 的 orderId。
   *
   * <p>這只決定 Kafka message key，不影響 outbox row 的 {@code aggregateid}——後者恆為
   * orderId，因為事件所屬的 aggregate 是 Order，與分區策略無關。
   *
   * <p><strong>key 的正確形狀由 {@code StockPool} 的識別決定，不是由訂單決定。</strong>
   * key 存在的目的是讓「會搶同一列庫存」的事件排進同一個 partition，因此兩者必須一起演進：
   * 目前 {@code stock_pools} 仍以 {@code (sku)} 唯一，同碼 SKU 真的共用一列，所以裸 sku 就是
   * 正確的 key。等庫存加上 {@code owner_id} 的那一刻，這裡必須在<strong>同一個 change</strong>
   * 內跟著改成 {@code ownerId:skuCode}——分開做的話，中間那段時間不同貨主的事件會擠進同一個
   * partition 排隊等一個它們其實不共用的鎖。
   *
   * <p><strong>而這個策略本身有到期日，而且只有一條出路。</strong> 它的第二個前提是「一張單
   * 只碰一個 SKU」，一則事件才摺得出單一個 key（見 {@code LineSnapshot.requireSingleSku}）。
   *
   * <p>放寬多 SKU 之後，這個策略與 ship-complete <strong>根本衝突</strong>：整籃原子判斷要在
   * 同一個交易裡檢查所有 SKU 的 ATP，而 per-SKU 分區的保證是「同一個 SKU 的事件由同一個
   * writer 序列化」——跨 SKU 的交易必然跨越多個 writer 的管轄，保證直接失效。
   *
   * <p>換更複雜的 key 救不回來：把整張單的 SKU 集合雜湊成 key 也不行，因為同一個 SKU 會出現
   * 在多種組合裡，仍然跨 writer。<strong>問題不在 key 的組成，在「一次交易碰多個資源」與
   * 「一個 key 只指向一個 partition」之間的矛盾。</strong>
   *
   * <p>「把事件按 SKU 拆成多則」<strong>不是出路</strong>——拆開之後一張單的兩則事件被兩個
   * writer 各自處理，沒有人看得到整張單，那正是 ship-complete 要求的東西。唯一的出路是本策略
   * 退場、退回 orderId，single-writer 的保證改用別的手段取得（按 SKU 分片的處理器、悲觀鎖，
   * 或單純接受樂觀鎖重試）。
   *
   * <p>SKU 以 {@link Supplier} 傳入而非直接傳值，是為了讓上一段的第二條出路真的走得通：
   * order-id 策略根本不需要 SKU，若在呼叫端就先算出來，多 SKU 的訂單會在
   * {@code requireSingleSku} 拋錯——**一個不需要 SKU 的策略，被迫先算出 SKU 才能執行**。
   * 延後求值之後，退回 order-id 就真的能跑，而不只是名義上的出路。
   */
  private String partitionKey(UUID orderId, Supplier<String> skuCode) {
    return SKU_STRATEGY.equals(partitionKeyStrategy) ? skuCode.get() : orderId.toString();
  }
}
