package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.IntegrationEventHandler;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import org.springframework.stereotype.Component;

@Component
class OrderPlacedIntegrationEventHandler
    implements IntegrationEventHandler<OrderPlacedIntegrationEvent> {

  private final AllocateOrderUsecase allocateOrderUsecase;

  OrderPlacedIntegrationEventHandler(AllocateOrderUsecase allocateOrderUsecase) {
    this.allocateOrderUsecase = allocateOrderUsecase;
  }

  @Override
  public String topic() {
    return IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC;
  }

  @Override
  public Class<OrderPlacedIntegrationEvent> eventClass() {
    return OrderPlacedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderPlacedIntegrationEvent event, MessageMetadata metadata) {
    AllocateOrderCommand allocateOrderCommand = new AllocateOrderCommand(event.getOrderId());
    allocateOrderUsecase.handle(new InboundCommand<>(allocateOrderCommand, metadata));
  }
}
