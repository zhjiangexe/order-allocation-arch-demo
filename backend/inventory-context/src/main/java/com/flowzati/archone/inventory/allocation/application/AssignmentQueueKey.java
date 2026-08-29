package com.flowzati.archone.inventory.allocation.application;

import java.util.UUID;

/** One stock-consumption FIFO queue, scoped only by the facts that establish contention. */
public record AssignmentQueueKey(UUID ownerId, UUID fromLocationId, String skuCode) {

    public AssignmentQueueKey {
        if (ownerId == null || fromLocationId == null || skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("Pending operation queue requires owner, source location and SKU");
        }
    }
}
