package com.flowzati.archone.inventory.allocation.application.result;

import java.util.UUID;

/** One committed move-to-quant quantity in an assignment result. */
public record AssignedMoveLine(UUID stockQuantId, int quantity) {

    public AssignedMoveLine {
        if (stockQuantId == null || quantity <= 0) {
            throw new IllegalArgumentException("Assigned move line requires stock quant and positive quantity");
        }
    }
}
