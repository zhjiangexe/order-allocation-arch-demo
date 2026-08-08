package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.messaging.api.InboundCommand;
import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import org.springframework.stereotype.Component;

/** Maps a committed stock-availability fact to one bounded backorder-allocation transaction. */
@Component
class StockAvailabilityIncreasedIntegrationEventHandler
    implements IntegrationEventHandler<StockAvailabilityIncreasedIntegrationEvent> {

  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase;
  private final AllocationRetryExecutor retryExecutor;

  StockAvailabilityIncreasedIntegrationEventHandler(
      AllocateWaitingDemandUsecase allocateWaitingDemandUsecase,
      AllocationRetryExecutor retryExecutor
  ) {
    this.allocateWaitingDemandUsecase = allocateWaitingDemandUsecase;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String destination() {
    return InventoryEventTopics.STOCK_EVENTS;
  }

  @Override
  public String eventType() {
    return StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE;
  }

  @Override
  public Class<StockAvailabilityIncreasedIntegrationEvent> eventClass() {
    return StockAvailabilityIncreasedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(
      StockAvailabilityIncreasedIntegrationEvent event,
      MessageMetadata metadata
  ) {
    AllocateWaitingDemandCommand command = new AllocateWaitingDemandCommand(
        event.getOwnerId(), event.getFacilityId(), event.getLocationId(), event.getSku());
    InboundCommand<AllocateWaitingDemandCommand> inbound = new InboundCommand<>(command, metadata);
    retryExecutor.execute(
        new AllocationRetryContext(
            "allocate-waiting-demand-after-availability-increase",
            metadata.eventId(), null, event.getSku()),
        () -> allocateWaitingDemandUsecase.handle(inbound));
  }
}
