package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.application.retry.AllocationRetryContext;
import com.flowzati.archone.allocation.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.allocation.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import org.springframework.stereotype.Component;

@Component
class StockReplenishedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<StockReplenishedIntegrationEvent> {

  private final ReplenishmentUsecase replenishmentUsecase;
  private final AllocationRetryExecutor retryExecutor;

  StockReplenishedIntegrationEventHandler(
      ReplenishmentUsecase replenishmentUsecase,
      AllocationRetryExecutor retryExecutor
  ) {
    this.replenishmentUsecase = replenishmentUsecase;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String topic() {
    return InventoryEventTopics.STOCK_EVENTS;
  }

  @Override
  public Class<StockReplenishedIntegrationEvent> eventClass() {
    return StockReplenishedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(StockReplenishedIntegrationEvent event, MessageMetadata metadata) {
    ReplenishStockCommand command = new ReplenishStockCommand(
        event.getOwnerId(),
        event.getNodeId(),
        event.getSku(),
        event.getInDate(),
        event.getExpiryDate(),
        event.getQuantity());
    InboundCommand<ReplenishStockCommand> inbound = new InboundCommand<>(command, metadata);
    retryExecutor.execute(
        new AllocationRetryContext("replenish-stock", metadata.eventId(), null, event.getSku()),
        () -> replenishmentUsecase.handle(inbound));
  }
}
