package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.messaging.api.InboundCommand;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
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
    AllocateOrderCommand allocateOrderCommand = new AllocateOrderCommand(event.getOrderId());
    InboundCommand<AllocateOrderCommand> inbound = new InboundCommand<>(allocateOrderCommand, metadata);
    retryExecutor.execute(
        // sku 傳 null（record 會正規化成 "unknown"）：對外事件不再帶 SKU，而為了一個日誌
        // 欄位就把它加回 payload 會讓 translator 的延後求值再次失效。orderId 還在，要查
        // SKU 從訂單查得到。
        new AllocationRetryContext(
            "allocate-order", metadata.eventId(), event.getOrderId().toString(), null),
        () -> allocateOrderUsecase.handle(inbound));
  }
}
