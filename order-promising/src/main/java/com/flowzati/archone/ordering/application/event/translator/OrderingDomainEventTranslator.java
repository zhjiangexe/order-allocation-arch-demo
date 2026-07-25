package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
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
    outboxAppender.append(
        new OrderPlacedIntegrationEvent(
            IdGenerator.nextId(), event.orderId(), event.sku(), event.quantity(), event.placedAt()),
        OutboxAggregateTypes.ORDER,
        aggregateId(event.orderId(), event.sku()),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.placedAt()
    );
  }

  @EventListener
  public void translate(OrderCancelled event) {
    outboxAppender.append(
        new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt()),
        OutboxAggregateTypes.ORDER,
        aggregateId(event.orderId(), event.sku()),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.cancelledAt()
    );
  }

  /**
   * sku 策略下用 SKU 當 partition key，讓同一個 SKU 的事件全部收斂進同一個
   * partition（single-writer，見 docs/superpowers/specs/2026-07-26-v3-single-writer-design.md）；
   * 預設（或任何非 "sku" 的值）沿用 v1 的 orderId。
   */
  private String aggregateId(UUID orderId, String sku) {
    return SKU_STRATEGY.equals(partitionKeyStrategy) ? sku : orderId.toString();
  }
}
