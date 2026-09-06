package com.flowzati.archone.inventory.allocation.entrypoint.temporal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.orchestration.contract.activity.inventory.RequestAllocationActivityInput;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalInventoryAllocationActivitiesAdapterTest {

    private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
    private final TemporalInventoryAllocationActivitiesAdapter activities =
            new TemporalInventoryAllocationActivitiesAdapter(allocateOrderUsecase);

    @Test
    void mapsWorkflowInputToAllocationCommand() {
        UUID orderId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");

        activities.requestAllocation(new RequestAllocationActivityInput("process-1", orderId, occurredAt));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
    }
}
