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
    // 摺疊一律經過具名的 requireSingleLine,而不是各自寫 lines().getFirst()。
    LineSnapshot line = LineSnapshot.requireSingleLine(event.lines());
    outboxAppender.append(
        new OrderPlacedIntegrationEvent(
            IdGenerator.nextId(), event.orderId(), line.skuCode(), line.quantity(),
            event.placedAt()),
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        new OutboxDelivery(
            IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
            partitionKey(event.orderId(), line.skuCode())),
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
                event.orderId(), LineSnapshot.requireSingleLine(event.lines()).skuCode())),
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
   */
  private String partitionKey(UUID orderId, String sku) {
    return SKU_STRATEGY.equals(partitionKeyStrategy) ? sku : orderId.toString();
  }
}
