package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.retry.AllocationRetryContext;
import com.flowzati.archone.allocation.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.allocation.application.usecase.ReleaseReservationUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.IntegrationEventHandler;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderCancelledIntegrationEventHandler
    implements IntegrationEventHandler<OrderCancelledIntegrationEvent> {

  private final ReleaseReservationUsecase releaseReservationUsecase;
  private final AllocationRetryExecutor retryExecutor;

  OrderCancelledIntegrationEventHandler(
      ReleaseReservationUsecase releaseReservationUsecase,
      AllocationRetryExecutor retryExecutor
  ) {
    this.releaseReservationUsecase = releaseReservationUsecase;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String topic() {
    return OrderingEventTopics.ORDER_EVENTS;
  }

  @Override
  public Class<OrderCancelledIntegrationEvent> eventClass() {
    return OrderCancelledIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderCancelledIntegrationEvent event, MessageMetadata metadata) {
    ReleaseReservationCommand releaseReservationCommand = new ReleaseReservationCommand(event.getOrderId());
    InboundCommand<ReleaseReservationCommand> inbound = new InboundCommand<>(releaseReservationCommand, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "release-reservation", metadata.eventId(), event.getOrderId().toString(), null),
        () -> releaseReservationUsecase.handle(inbound));
  }
}
