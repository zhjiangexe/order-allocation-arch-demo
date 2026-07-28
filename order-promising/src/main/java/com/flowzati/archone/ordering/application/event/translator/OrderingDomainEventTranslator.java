package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.outbox.OutboxDelivery;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.util.UUID;
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
            IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
            partitionKey(event.orderId(), skuCode)),
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
            IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
            partitionKey(
                event.orderId(), LineSnapshot.requireSingleSku(event.lines()))),
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
   * <p><strong>而這個策略本身有到期日。</strong> 它的第二個前提是「一張單只碰一個 SKU」，
   * 一則事件才摺得出單一個 key（見 {@code LineSnapshot.requireSingleSku}）。放寬多 SKU 之後
   * 一則 {@code OrderPlaced} 無法同時進兩個 partition，屆時要嘛事件按 SKU 拆開，要嘛策略退場、
   * 壓測改用 order-id。換 key 解決不了這一層。
   */
  private String partitionKey(UUID orderId, String sku) {
    return SKU_STRATEGY.equals(partitionKeyStrategy) ? sku : orderId.toString();
  }
}
