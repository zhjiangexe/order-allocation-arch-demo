package com.flowzati.archone.inventory.allocation.domain.valueobject;

import java.util.UUID;

/** Immutable planning demand copied from one confirmed {@code StockMove}. */
public record StockMoveDemand(
        UUID moveId, Long moveVersion, String sourceLineId, int lineSequence, String skuCode, int quantity) {

    public StockMoveDemand {
        if (moveId == null
                || moveVersion == null
                || sourceLineId == null
                || sourceLineId.isBlank()
                || lineSequence <= 0
                || skuCode == null
                || skuCode.isBlank()
                || quantity <= 0) {
            throw new IllegalArgumentException("Stock move demand is invalid");
        }
    }
}
