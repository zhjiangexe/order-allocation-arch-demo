package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import com.flowzati.archone.stock.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderAllocatedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<OrderAllocatedIntegrationEvent> {

  private final RecordOrderAllocationUsecase recordOrderAllocationUsecase;

  OrderAllocatedIntegrationEventHandler(RecordOrderAllocationUsecase recordOrderAllocationUsecase) {
    this.recordOrderAllocationUsecase = recordOrderAllocationUsecase;
  }

  @Override
  public String topic() {
    return PromisingEventTopics.ALLOCATION_EVENTS;
  }

  @Override
  public Class<OrderAllocatedIntegrationEvent> eventClass() {
    return OrderAllocatedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderAllocatedIntegrationEvent event, MessageMetadata metadata) {
    RecordOrderAllocationCommand command = new RecordOrderAllocationCommand(event.getOrderId(), event.getAllocatedAt());
    recordOrderAllocationUsecase.handle(new InboundCommand<>(command, metadata));
  }
}
