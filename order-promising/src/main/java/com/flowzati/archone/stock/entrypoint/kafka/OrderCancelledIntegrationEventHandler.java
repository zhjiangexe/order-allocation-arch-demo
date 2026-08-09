package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderCancelledIntegrationEventHandler
    implements IntegrationEventHandler<OrderCancelledIntegrationEvent> {

  private final CancelMovementsUsecase cancelMovementsUsecase;

  OrderCancelledIntegrationEventHandler(CancelMovementsUsecase cancelMovementsUsecase) {
    this.cancelMovementsUsecase = cancelMovementsUsecase;
  }

  @Override
  public String destination() {
    return OrderingEventTopics.ORDER_EVENTS;
  }

  @Override
  public String eventType() {
    return OrderCancelledIntegrationEvent.EVENT_TYPE;
  }

  @Override
  public Class<OrderCancelledIntegrationEvent> eventClass() {
    return OrderCancelledIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderCancelledIntegrationEvent event, MessageMetadata metadata) {
    cancelMovementsUsecase.execute(new CancelMovementsCommand(event.getOrderId()));
  }
}
