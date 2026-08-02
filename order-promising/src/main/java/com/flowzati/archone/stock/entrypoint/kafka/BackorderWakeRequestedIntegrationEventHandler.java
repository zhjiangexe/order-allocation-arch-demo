package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.WakeBackordersCommand;
import com.flowzati.archone.stock.application.event.BackorderWakeRequestedIntegrationEvent;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import org.springframework.stereotype.Component;

/**
 * 續做喚醒。與補貨事件同一個 topic，因此同一個 {@code (貨主, 倉, SKU)} 的兩類訊息由同一個
 * consumer 依序處理——續做不會與它要接續的那一輪並行。
 */
@Component
class BackorderWakeRequestedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<BackorderWakeRequestedIntegrationEvent> {

  private final ReplenishmentUsecase replenishmentUsecase;
  private final StockLocationRepository stockLocationRepository;
  private final AllocationRetryExecutor retryExecutor;

  BackorderWakeRequestedIntegrationEventHandler(
      ReplenishmentUsecase replenishmentUsecase,
      StockLocationRepository stockLocationRepository,
      AllocationRetryExecutor retryExecutor
  ) {
    this.replenishmentUsecase = replenishmentUsecase;
    this.stockLocationRepository = stockLocationRepository;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String topic() {
    return InventoryEventTopics.STOCK_EVENTS;
  }

  @Override
  public Class<BackorderWakeRequestedIntegrationEvent> eventClass() {
    return BackorderWakeRequestedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(
      BackorderWakeRequestedIntegrationEvent event, MessageMetadata metadata) {
    WakeBackordersCommand command = new WakeBackordersCommand(
        event.getOwnerId(),
        event.getNodeId(),
        internalLocationOf(event.getNodeId()),
        event.getSku());
    InboundCommand<WakeBackordersCommand> inbound = new InboundCommand<>(command, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "wake-backorders", metadata.eventId(), null, event.getSku()),
        () -> replenishmentUsecase.handleWake(inbound));
  }

  /**
   * 倉 → 該倉的內部位置，與補貨的 handler 同一個判斷：對外說倉、對內說位置，翻譯在
   * entrypoint。倉沒有內部位置時拋錯——那個倉不可能有貨，續做喚醒它是在對一個空集合工作。
   */
  private java.util.UUID internalLocationOf(java.util.UUID nodeId) {
    return stockLocationRepository.findInternalOf(nodeId)
        .map(com.flowzati.archone.catalog.domain.model.StockLocation::getId)
        .orElseThrow(() -> new IllegalStateException(
            "Warehouse " + nodeId + " has no internal stock location"));
  }
}
