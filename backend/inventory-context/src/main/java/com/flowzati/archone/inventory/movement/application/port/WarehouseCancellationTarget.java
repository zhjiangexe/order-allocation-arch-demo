package com.flowzati.archone.inventory.movement.application.port;

import java.util.UUID;

/** The Stock Operation whose warehouse execution must be cancelled. */
public record WarehouseCancellationTarget(UUID stockOperationId) {

    public WarehouseCancellationTarget {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Stock operation ID is required");
        }
    }
}
