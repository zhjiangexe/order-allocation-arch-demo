package com.flowzati.archone.inventory.allocation.application.invocation;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import java.util.Objects;
import java.util.UUID;

/** Requests one assignment attempt from an already identified pending-operation queue. */
public record AssignNextPendingStockOperationCommand(AssignmentQueueKey queueKey) {

    public AssignNextPendingStockOperationCommand {
        Objects.requireNonNull(queueKey, "Assignment queue key is required");
    }

    public static AssignNextPendingStockOperationCommand forQueue(UUID ownerId, UUID fromLocationId, String skuCode) {
        return new AssignNextPendingStockOperationCommand(new AssignmentQueueKey(ownerId, fromLocationId, skuCode));
    }
}
