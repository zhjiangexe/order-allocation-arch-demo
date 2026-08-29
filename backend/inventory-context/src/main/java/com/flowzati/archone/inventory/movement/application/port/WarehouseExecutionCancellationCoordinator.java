package com.flowzati.archone.inventory.movement.application.port;

import java.util.UUID;

/** External port invoked without an Inventory transaction or held stock lock. */
public interface WarehouseExecutionCancellationCoordinator {

    Decision cancelExecution(Target target, UUID cancellationOperationId);

    enum Decision {
        CONFIRMED,
        REJECTED
    }

    record Target(UUID stockOperationId) {

        public Target {
            if (stockOperationId == null) {
                throw new IllegalArgumentException("Stock operation ID is required");
            }
        }
    }
}
