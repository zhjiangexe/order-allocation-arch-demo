package com.flowzati.archone.inventory.movement.infrastructure;

import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationDecision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationTarget;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Safe capability gate until a production non-order source supplies its own coordinator. */
@Component
public class RejectingExecutionCancellationAdapter implements WarehouseExecutionCancellationCoordinator {

    @Override
    public WarehouseCancellationDecision cancelExecution(
            WarehouseCancellationTarget target, UUID cancellationOperationId) {
        return WarehouseCancellationDecision.REJECTED;
    }
}
