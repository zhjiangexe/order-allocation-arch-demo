package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderingDomainEventTranslator {

  private final OutboxAppender outboxAppender;

  public OrderingDomainEventTranslator(OutboxAppender outboxAppender) {
    this.outboxAppender = outboxAppender;
  }

  @EventListener
  public void translate(OrderPlaced event) {
    outboxAppender.append(
        new OrderPlacedIntegrationEvent(
            IdGenerator.nextId(), event.orderId(), event.sku(), event.quantity(), event.placedAt()),
        "Order",
        event.orderId(),
        event.placedAt()
    );
  }

  @EventListener
  public void translate(OrderCancelled event) {
    outboxAppender.append(
        new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt()),
        "Order",
        event.orderId(),
        event.cancelledAt()
    );
  }
}
