package com.flowzati.archone.inventory.movement.application.command;

import com.flowzati.archone.inventory.movement.domain.WarehouseCancellationCheckpoint;
import java.util.UUID;

/** Target-side command for one idempotent cancellation of an Inventory operation group. */
public record CancelStockOperationCommand(
        UUID stockOperationId,
        UUID cancellationOperationId,
        WarehouseCancellationCheckpoint warehouseCancellationCheckpoint) {

    public CancelStockOperationCommand {
        if (stockOperationId == null || cancellationOperationId == null || warehouseCancellationCheckpoint == null) {
            throw new IllegalArgumentException(
                    "Stock operation, cancellation operation and warehouse checkpoint are required");
        }
    }

    public static CancelStockOperationCommand requiringWarehouseConfirmation(
            UUID stockOperationId, UUID cancellationOperationId) {
        return new CancelStockOperationCommand(
                stockOperationId, cancellationOperationId, WarehouseCancellationCheckpoint.REQUIRED);
    }

    public static CancelStockOperationCommand afterWarehouseConfirmation(
            UUID stockOperationId, UUID cancellationOperationId) {
        return new CancelStockOperationCommand(
                stockOperationId, cancellationOperationId, WarehouseCancellationCheckpoint.CONFIRMED);
    }
}
