package com.flowzati.archone.inventory.allocation.domain;

import java.util.UUID;

/** Pure proposal detail; it becomes a StockMoveLine only inside the assignment transaction. */
public record ProposedMoveLine(UUID moveId, UUID stockQuantId, int quantity) {

    public ProposedMoveLine {
        if (moveId == null || stockQuantId == null || quantity <= 0) {
            throw new IllegalArgumentException("Proposed move line requires move, stock quant and positive quantity");
        }
    }
}
