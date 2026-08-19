package com.flowzati.archone.inventory.balance.domain.event;

import com.flowzati.archone.foundation.domain.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** A completed inbound movement made physical stock available at one location. */
public record StockAvailabilityIncreased(
        UUID ownerId, UUID facilityId, UUID locationId, String skuCode, int quantity, Instant occurredAt)
        implements DomainEvent {

    public StockAvailabilityIncreased {
        if (ownerId == null || facilityId == null || locationId == null) {
            throw new IllegalArgumentException("Owner, facility and location IDs are required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Availability increase must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Occurred time is required");
        }
    }
}
