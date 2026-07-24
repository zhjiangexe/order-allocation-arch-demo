package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.usecase.ReleaseReservationUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.IntegrationEventHandler;
import com.flowzati.archone.common.outbox.OutboxRoutes;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import org.springframework.stereotype.Component;

@Component
class OrderCancelledIntegrationEventHandler
    implements IntegrationEventHandler<OrderCancelledIntegrationEvent> {

  private final ReleaseReservationUsecase releaseReservationUsecase;

  OrderCancelledIntegrationEventHandler(ReleaseReservationUsecase releaseReservationUsecase) {
    this.releaseReservationUsecase = releaseReservationUsecase;
  }

  @Override
  public String topic() {
    return OutboxRoutes.ORDERING_ORDER_EVENTS;
  }

  @Override
  public Class<OrderCancelledIntegrationEvent> eventClass() {
    return OrderCancelledIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderCancelledIntegrationEvent event, MessageMetadata metadata) {
    ReleaseReservationCommand releaseReservationCommand = new ReleaseReservationCommand(event.getOrderId());
    releaseReservationUsecase.handle(new InboundCommand<>(releaseReservationCommand, metadata));
  }
}
