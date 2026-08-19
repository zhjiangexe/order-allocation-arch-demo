package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.command.CancelMovementsCommand;
import com.flowzati.archone.inventory.allocation.application.service.reservation.TransactionalAllocationAttempt;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.allocation.application.usecase.CancelMovementsUsecase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationIntegrationEventConsumersTest {

    @Test
    void shouldTranslateOrderLifecycleEventsToAllocationCommands() {
        AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
        CancelMovementsUsecase cancelMovementsUsecase = mock(CancelMovementsUsecase.class);
        AllocationOrderLifecycleEventConsumer consumer =
                new AllocationOrderLifecycleEventConsumer(allocateOrderUsecase, cancelMovementsUsecase);
        UUID orderId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-10T02:00:00Z");

        consumer.onOrderPlaced(new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, occurredAt));
        UUID cancellationEventId = UUID.randomUUID();
        consumer.onOrderCancelled(new OrderCancelledIntegrationEvent(cancellationEventId, orderId, occurredAt));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
        verify(cancelMovementsUsecase).execute(new CancelMovementsCommand(orderId, cancellationEventId));
    }

    @Test
    void shouldTranslateAvailabilityEventToOneBoundedWaitingDemandCommand() {
        TransactionalAllocationAttempt allocationAttempt = mock(TransactionalAllocationAttempt.class);
        AllocationInventoryAvailabilityEventConsumer consumer =
                new AllocationInventoryAvailabilityEventConsumer(allocationAttempt);
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        consumer.onStockAvailabilityIncreased(new StockAvailabilityIncreasedIntegrationEvent(
                UUID.randomUUID(), ownerId, facilityId, locationId, "SKU-1", 5));

        verify(allocationAttempt).attempt(new AllocateWaitingDemandCommand(ownerId, facilityId, locationId, "SKU-1"));
    }
}
