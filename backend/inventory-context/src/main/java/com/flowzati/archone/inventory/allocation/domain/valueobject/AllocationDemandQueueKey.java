package com.flowzati.archone.inventory.allocation.domain.valueobject;

import java.util.UUID;

/** Identifies one FIFO queue of allocation demands competing for the same SKU stock. */
public record AllocationDemandQueueKey(UUID ownerId, UUID facilityId, UUID locationId, String skuCode) {

    public AllocationDemandQueueKey {
        if (ownerId == null || facilityId == null || locationId == null) {
            throw new IllegalArgumentException("Owner, facility, and location IDs are required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
    }
}
