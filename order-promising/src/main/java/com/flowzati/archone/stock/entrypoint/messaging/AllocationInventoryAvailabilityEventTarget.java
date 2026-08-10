package com.flowzati.archone.stock.entrypoint.messaging;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import org.springframework.stereotype.Component;

/** Allocation-owned target for physical availability facts received from Inventory. */
@Component
public final class AllocationInventoryAvailabilityEventTarget {

  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase;

  public AllocationInventoryAvailabilityEventTarget(
      AllocateWaitingDemandUsecase allocateWaitingDemandUsecase
  ) {
    this.allocateWaitingDemandUsecase = allocateWaitingDemandUsecase;
  }

  public void onStockAvailabilityIncreased(
      StockAvailabilityIncreasedIntegrationEvent event
  ) {
    allocateWaitingDemandUsecase.execute(new AllocateWaitingDemandCommand(
        event.getOwnerId(), event.getFacilityId(), event.getLocationId(), event.getSku()));
  }
}
