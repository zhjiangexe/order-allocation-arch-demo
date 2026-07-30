package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.allocation.application.command.WakeBackordersCommand;
import com.flowzati.archone.allocation.application.event.BackorderWakeRequestedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.allocation.application.retry.AllocationRetryContext;
import com.flowzati.archone.allocation.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.allocation.application.usecase.ReplenishmentUsecase;
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
  private final AllocationRetryExecutor retryExecutor;

  BackorderWakeRequestedIntegrationEventHandler(
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
  public Class<BackorderWakeRequestedIntegrationEvent> eventClass() {
    return BackorderWakeRequestedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(
      BackorderWakeRequestedIntegrationEvent event, MessageMetadata metadata) {
    WakeBackordersCommand command = new WakeBackordersCommand(event.getOwnerId(), event.getNodeId(), event.getSku());
    InboundCommand<WakeBackordersCommand> inbound = new InboundCommand<>(command, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "wake-backorders", metadata.eventId(), null, event.getSku()),
        () -> replenishmentUsecase.handleWake(inbound));
  }
}
