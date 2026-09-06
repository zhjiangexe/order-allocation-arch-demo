package com.flowzati.archone.inventory.movement.application.invocation;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.UUID;

/** Cancels a source's movements after warehouse execution has reached a safe terminal state. */
public record CancelSourceStockMovementsCommand(StockOperationSource source, UUID cancellationOperationId) {

    public CancelSourceStockMovementsCommand {
        if (source == null || cancellationOperationId == null) {
            throw new IllegalArgumentException("Movement source and cancellation operation are required");
        }
    }
}
