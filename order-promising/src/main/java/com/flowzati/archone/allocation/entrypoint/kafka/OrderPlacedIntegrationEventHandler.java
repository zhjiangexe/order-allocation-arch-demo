package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.retry.AllocationRetryContext;
import com.flowzati.archone.allocation.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.IntegrationEventHandler;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import org.springframework.stereotype.Component;

@Component
class OrderPlacedIntegrationEventHandler
    implements IntegrationEventHandler<OrderPlacedIntegrationEvent> {

  private final AllocateOrderUsecase allocateOrderUsecase;
  private final AllocationRetryExecutor retryExecutor;

  OrderPlacedIntegrationEventHandler(
      AllocateOrderUsecase allocateOrderUsecase,
      AllocationRetryExecutor retryExecutor
  ) {
    this.allocateOrderUsecase = allocateOrderUsecase;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String topic() {
    return OrderingEventTopics.ORDER_EVENTS;
  }

  @Override
  public Class<OrderPlacedIntegrationEvent> eventClass() {
    return OrderPlacedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(OrderPlacedIntegrationEvent event, MessageMetadata metadata) {
    AllocateOrderCommand allocateOrderCommand = new AllocateOrderCommand(event.getOrderId());
    InboundCommand<AllocateOrderCommand> inbound = new InboundCommand<>(allocateOrderCommand, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "allocate-order", metadata.eventId(), event.getOrderId().toString(), event.getSku()),
        () -> allocateOrderUsecase.handle(inbound));
  }
}
