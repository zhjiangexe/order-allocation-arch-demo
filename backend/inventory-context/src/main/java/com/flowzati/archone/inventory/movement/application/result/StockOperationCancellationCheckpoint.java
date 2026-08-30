package com.flowzati.archone.inventory.movement.application.result;

import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationTarget;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;

/** Durable cancellation target and the latest persisted coordination state. */
public record StockOperationCancellationCheckpoint(
        WarehouseCancellationTarget target, StockOperationCancellationState state) {

    public StockOperationCancellationCheckpoint {
        if (target == null || state == null) {
            throw new IllegalArgumentException("Cancellation target and state are required");
        }
    }
}
