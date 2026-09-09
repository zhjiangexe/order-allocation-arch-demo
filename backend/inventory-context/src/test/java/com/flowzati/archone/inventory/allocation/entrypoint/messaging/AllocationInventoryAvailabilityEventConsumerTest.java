package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.usecase.AssignNextStockOperationUsecase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationInventoryAvailabilityEventConsumerTest {

    @Test
    void shouldTranslateAvailabilityEventToOneBoundedWaitingDemandCommand() {
        AssignNextStockOperationUsecase assignNextStockOperationUsecase = mock(AssignNextStockOperationUsecase.class);
        AllocationInventoryAvailabilityEventConsumer consumer =
                new AllocationInventoryAvailabilityEventConsumer(assignNextStockOperationUsecase);
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        consumer.onStockAvailabilityIncreased(new StockAvailabilityIncreasedIntegrationEvent(
                UUID.randomUUID(), ownerId, facilityId, locationId, "SKU-1", 5));

        verify(assignNextStockOperationUsecase).execute(new AssignmentQueueKey(ownerId, locationId, "SKU-1"));
    }
}
