package com.flowzati.archone.inventory.entrypoint.temporal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalInventoryActivitiesAdapterTest {

    private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase =
            mock(CompleteOutboundMovementsUsecase.class);
    private final TemporalInventoryActivitiesAdapter activities =
            new TemporalInventoryActivitiesAdapter(allocateOrderUsecase, completeOutboundMovementsUsecase);

    @Test
    void mapsWorkflowInputsToInventoryCommands() {
        UUID orderId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");

        activities.requestAllocation(new RequestAllocationActivityInput("process-1", orderId, occurredAt));
        activities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                "process-1", orderId, allocationId, shipmentId, List.of(movementId), occurredAt));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
        verify(completeOutboundMovementsUsecase)
                .execute(new CompleteOutboundMovementsCommand(
                        allocationId, orderId, shipmentId, List.of(movementId), occurredAt));
    }
}
