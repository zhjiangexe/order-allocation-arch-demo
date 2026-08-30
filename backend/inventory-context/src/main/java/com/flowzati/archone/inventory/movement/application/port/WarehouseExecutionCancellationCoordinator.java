package com.flowzati.archone.inventory.movement.application.port;

import java.util.UUID;

/** External port invoked without an Inventory transaction or held stock lock. */
public interface WarehouseExecutionCancellationCoordinator {

    WarehouseCancellationDecision cancelExecution(WarehouseCancellationTarget target, UUID cancellationOperationId);
}
