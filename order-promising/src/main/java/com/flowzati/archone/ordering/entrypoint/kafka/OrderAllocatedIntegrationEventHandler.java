package com.flowzati.archone.ordering.entrypoint.kafka;

import com.flowzati.archone.stock.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import com.flowzati.archone.ordering.application.command.ConfirmAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.ConfirmOrderUsecase;
import org.springframework.stereotype.Component;

@Component
class OrderAllocatedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<OrderAllocatedIntegrationEvent> {

  private final ConfirmOrderUsecase confirmOrderUsecase;

  OrderAllocatedIntegrationEventHandler(ConfirmOrderUsecase confirmOrderUsecase) {
    this.confirmOrderUsecase = confirmOrderUsecase;
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
    confirmOrderUsecase.confirmAllocated(new InboundCommand<>(
        new ConfirmAllocationCommand(event.getOrderId(), event.getAllocatedAt()), metadata));
  }
}
