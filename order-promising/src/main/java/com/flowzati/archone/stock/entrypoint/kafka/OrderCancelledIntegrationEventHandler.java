package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.messaging.api.InboundCommand;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderCancelledIntegrationEventHandler
    implements IntegrationEventHandler<OrderCancelledIntegrationEvent> {

  private final CancelMovementsUsecase cancelMovementsUsecase;
  private final AllocationRetryExecutor retryExecutor;

  OrderCancelledIntegrationEventHandler(
      CancelMovementsUsecase cancelMovementsUsecase,
      AllocationRetryExecutor retryExecutor
  ) {
    this.cancelMovementsUsecase = cancelMovementsUsecase;
    this.retryExecutor = retryExecutor;
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
    CancelMovementsCommand releaseReservationCommand = new CancelMovementsCommand(event.getOrderId());
    InboundCommand<CancelMovementsCommand> inbound = new InboundCommand<>(releaseReservationCommand, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "release-reservation", metadata.eventId(), event.getOrderId().toString(), null),
        () -> cancelMovementsUsecase.handle(inbound));
  }
}
