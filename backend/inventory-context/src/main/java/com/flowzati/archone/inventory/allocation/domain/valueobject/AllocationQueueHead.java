package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import java.time.Instant;
import java.util.UUID;

/** The earliest committed pending demand currently visible in one shared-SKU FIFO queue. */
public record AllocationQueueHead(
        String skuCode, UUID allocationDemandId, AllocationSourceType sourceType, Instant enqueuedAt) {

    public AllocationQueueHead {
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("Allocation queue-head SKU is required");
        }
        if (allocationDemandId == null || sourceType == null || enqueuedAt == null) {
            throw new IllegalArgumentException("Allocation queue-head identity and precedence are required");
        }
    }
}
