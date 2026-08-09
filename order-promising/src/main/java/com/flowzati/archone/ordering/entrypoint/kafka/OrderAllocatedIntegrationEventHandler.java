package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderAllocatedIntegrationEventHandler
    implements IntegrationEventHandler<OrderAllocatedIntegrationEvent> {

  private final RecordOrderAllocationUsecase recordOrderAllocationUsecase;

  OrderAllocatedIntegrationEventHandler(RecordOrderAllocationUsecase recordOrderAllocationUsecase) {
    this.recordOrderAllocationUsecase = recordOrderAllocationUsecase;
  }

  @Override
  public String destination() {
    return PromisingEventTopics.ALLOCATION_EVENTS;
  }

  @Override
  public String eventType() {
    return OrderAllocatedIntegrationEvent.EVENT_TYPE;
  }

  @Override
  public Class<OrderAllocatedIntegrationEvent> eventClass() {
    return OrderAllocatedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderAllocatedIntegrationEvent event, MessageMetadata metadata) {
    RecordOrderAllocationCommand command = new RecordOrderAllocationCommand(event.getOrderId(), event.getAllocatedAt());
    recordOrderAllocationUsecase.execute(command);
  }
}
