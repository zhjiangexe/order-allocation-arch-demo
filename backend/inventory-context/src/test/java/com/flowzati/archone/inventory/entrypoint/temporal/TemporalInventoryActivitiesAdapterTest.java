package com.flowzati.archone.inventory.entrypoint.temporal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.adapter.TemporalInventoryActivitiesAdapter;
import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalInventoryActivitiesAdapterTest {

    private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
    private final CompleteOutboundMovementsUsecase completeOperation = mock(CompleteOutboundMovementsUsecase.class);
    private final TemporalInventoryActivitiesAdapter activities =
            new TemporalInventoryActivitiesAdapter(allocateOrderUsecase, completeOperation);

    @Test
    void mapsWorkflowInputsToInventoryCommands() {
        UUID orderId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");

        activities.requestAllocation(new RequestAllocationActivityInput("process-1", orderId, occurredAt));
        activities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                "process-1", orderId, stockOperationId, shipmentId, List.of(movementId), occurredAt));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
        verify(completeOperation)
                .execute(new CompleteOutboundMovementsCommand(
                        orderId, shipmentId, stockOperationId, List.of(movementId), occurredAt));
    }
}
