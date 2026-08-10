package com.flowzati.archone.stock.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationIntegrationEventTargetsTest {

  @Test
  void shouldTranslateOrderLifecycleEventsToAllocationCommands() {
    AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
    CancelMovementsUsecase cancelMovementsUsecase = mock(CancelMovementsUsecase.class);
    AllocationOrderLifecycleEventTarget target = new AllocationOrderLifecycleEventTarget(
        allocateOrderUsecase, cancelMovementsUsecase);
    UUID orderId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-10T02:00:00Z");

    target.onOrderPlaced(new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), orderId, occurredAt));
    target.onOrderCancelled(new OrderCancelledIntegrationEvent(
        UUID.randomUUID(), orderId, occurredAt));

    verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
    verify(cancelMovementsUsecase).execute(new CancelMovementsCommand(orderId));
  }

  @Test
  void shouldTranslateAvailabilityEventToOneBoundedWaitingDemandCommand() {
    AllocateWaitingDemandUsecase usecase = mock(AllocateWaitingDemandUsecase.class);
    AllocationInventoryAvailabilityEventTarget target =
        new AllocationInventoryAvailabilityEventTarget(usecase);
    UUID ownerId = UUID.randomUUID();
    UUID facilityId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    target.onStockAvailabilityIncreased(new StockAvailabilityIncreasedIntegrationEvent(
        UUID.randomUUID(), ownerId, facilityId, locationId, "SKU-1", 5));

    verify(usecase).execute(new AllocateWaitingDemandCommand(
        ownerId, facilityId, locationId, "SKU-1"));
  }
}
