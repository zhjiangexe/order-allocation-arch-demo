package com.flowzati.archone.ordering.application.command;

import java.time.Instant;
import java.util.UUID;

/** stock context 的配貨完成事實，要記錄到這張訂單。 */
public record RecordOrderAllocationCommand(UUID orderId, Instant allocatedAt) {

    public RecordOrderAllocationCommand {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (allocatedAt == null) {
            throw new IllegalArgumentException("Allocated time is required");
        }
    }
}
