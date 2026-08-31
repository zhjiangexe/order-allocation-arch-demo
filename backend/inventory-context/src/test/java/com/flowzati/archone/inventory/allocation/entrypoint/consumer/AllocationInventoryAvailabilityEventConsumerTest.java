package com.flowzati.archone.inventory.allocation.entrypoint.consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationInventoryAvailabilityEventConsumerTest {

    @Test
    void shouldTranslateAvailabilityEventToOneBoundedWaitingDemandCommand() {
        StockOperationAssignmentCoordinator coordinator = mock(StockOperationAssignmentCoordinator.class);
        AllocationInventoryAvailabilityEventConsumer consumer =
                new AllocationInventoryAvailabilityEventConsumer(coordinator);
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        consumer.onStockAvailabilityIncreased(new StockAvailabilityIncreasedIntegrationEvent(
                UUID.randomUUID(), ownerId, facilityId, locationId, "SKU-1", 5));

        verify(coordinator).tryAssignNext(new AssignmentQueueKey(ownerId, locationId, "SKU-1"));
    }
}
