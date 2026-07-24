package com.flowzati.archone.allocation.application.event.translator;

import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AllocationDomainEventTranslator {

  private final OutboxAppender outboxAppender;

  public AllocationDomainEventTranslator(OutboxAppender outboxAppender) {
    this.outboxAppender = outboxAppender;
  }

  @EventListener
  public void translate(OrderAllocationCompleted event) {
    outboxAppender.append(
        new OrderAllocatedIntegrationEvent(
            IdGenerator.nextId(),
            event.orderId(),
            event.reservationId(),
            event.sku(),
            event.quantity(),
            event.allocatedAt()),
        "Order",
        event.orderId(),
        event.allocatedAt()
    );
  }

  @EventListener
  public void translate(OrderBackordered event) {
    outboxAppender.append(
        new BackorderCreatedIntegrationEvent(
            IdGenerator.nextId(),
            event.orderId(),
            event.sku(),
            event.quantity(),
            event.backorderedSince()),
        "Order",
        event.orderId(),
        event.backorderedSince()
    );
  }
}
