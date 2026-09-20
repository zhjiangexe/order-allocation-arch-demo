package com.flowzati.archone.inventory.balance.application.event;

import java.time.Instant;
import java.util.UUID;

/** Immutable application event stating that physical stock availability increased. */
public record StockAvailabilityIncreased(
        UUID ownerId, UUID facilityId, UUID locationId, String sku, int quantity, Instant occurredAt) {

    public StockAvailabilityIncreased {
        if (ownerId == null || facilityId == null || locationId == null) {
            throw new IllegalArgumentException("Stock availability scope is required");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("Stock availability SKU is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Stock availability quantity must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Stock availability occurrence time is required");
        }
    }
}
