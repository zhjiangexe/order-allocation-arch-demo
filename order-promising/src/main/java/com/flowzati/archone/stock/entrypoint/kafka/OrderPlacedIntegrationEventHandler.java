package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderPlacedIntegrationEventHandler
    implements IntegrationEventHandler<OrderPlacedIntegrationEvent> {

  private final AllocateOrderUsecase allocateOrderUsecase;

  OrderPlacedIntegrationEventHandler(AllocateOrderUsecase allocateOrderUsecase) {
    this.allocateOrderUsecase = allocateOrderUsecase;
  }

  @Override
  public String destination() {
    return OrderingEventTopics.ORDER_EVENTS;
  }

  @Override
  public String eventType() {
    return OrderPlacedIntegrationEvent.EVENT_TYPE;
  }

  @Override
  public Class<OrderPlacedIntegrationEvent> eventClass() {
    return OrderPlacedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderPlacedIntegrationEvent event, MessageMetadata metadata) {
    allocateOrderUsecase.execute(new AllocateOrderCommand(event.getOrderId()));
  }
}
