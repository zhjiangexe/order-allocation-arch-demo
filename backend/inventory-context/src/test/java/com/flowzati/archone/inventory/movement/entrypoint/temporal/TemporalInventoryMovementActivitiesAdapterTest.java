package com.flowzati.archone.inventory.movement.entrypoint.temporal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orchestration.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalInventoryMovementActivitiesAdapterTest {

    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase =
            mock(CompleteOutboundMovementsUsecase.class);
    private final TemporalInventoryMovementActivitiesAdapter activities =
            new TemporalInventoryMovementActivitiesAdapter(completeOutboundMovementsUsecase);

    @Test
    void mapsWorkflowInputToMovementCommand() {
        UUID orderId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");

        activities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                "process-1", orderId, stockOperationId, shipmentId, List.of(movementId), occurredAt));

        verify(completeOutboundMovementsUsecase)
                .execute(new CompleteOutboundMovementsCommand(
                        orderId, shipmentId, stockOperationId, List.of(movementId), occurredAt));
    }
}
