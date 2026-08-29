package com.flowzati.archone.inventory.movement.application.usecase;

import java.util.UUID;

/** Canonical completion target and whether this invocation performed the state change. */
public record SourceStockMovementsCompletionResult(UUID stockOperationId, boolean changed) {

    public SourceStockMovementsCompletionResult {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Completed operation ID is required");
        }
    }
}
