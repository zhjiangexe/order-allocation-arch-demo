package com.flowzati.archone.inventory.movement.application.command;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.UUID;

/** Source-side command; the application resolves this identity to one canonical operation. */
public record CancelSourceStockMovementsCommand(
        StockOperationSource source,
        UUID cancellationOperationId,
        WarehouseCancellationCheckpoint warehouseCancellationCheckpoint) {

    public CancelSourceStockMovementsCommand {
        if (source == null || cancellationOperationId == null || warehouseCancellationCheckpoint == null) {
            throw new IllegalArgumentException(
                    "Movement source, cancellation operation and warehouse checkpoint are required");
        }
    }

    public static CancelSourceStockMovementsCommand requiringWarehouseConfirmation(
            StockOperationSource source, UUID cancellationOperationId) {
        return new CancelSourceStockMovementsCommand(
                source, cancellationOperationId, WarehouseCancellationCheckpoint.REQUIRED);
    }

    public static CancelSourceStockMovementsCommand afterWarehouseConfirmation(
            StockOperationSource source, UUID cancellationOperationId) {
        return new CancelSourceStockMovementsCommand(
                source, cancellationOperationId, WarehouseCancellationCheckpoint.CONFIRMED);
    }
}
