package com.flowzati.archone.inventory.movement.application.result;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;

/** Latest persisted state of one durable stock-operation cancellation. */
public record StockOperationCancellationCheckpoint(StockOperationCancellationState state) {

    public StockOperationCancellationCheckpoint {
        if (state == null) {
            throw new IllegalArgumentException("Cancellation state is required");
        }
    }
}
