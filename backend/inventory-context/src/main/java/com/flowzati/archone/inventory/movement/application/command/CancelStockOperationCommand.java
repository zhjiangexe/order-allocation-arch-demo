package com.flowzati.archone.inventory.movement.application.command;

import java.util.UUID;

/** Cancels one Inventory operation after warehouse execution has reached a safe terminal state. */
public record CancelStockOperationCommand(UUID stockOperationId, UUID cancellationOperationId) {

    public CancelStockOperationCommand {
        if (stockOperationId == null || cancellationOperationId == null) {
            throw new IllegalArgumentException("Stock operation and cancellation operation are required");
        }
    }
}
