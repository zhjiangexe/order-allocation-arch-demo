package com.flowzati.archone.inventory.position.application;

import java.time.Instant;
import java.util.UUID;

/** Immutable application result describing newly available physical stock. */
public record StockAvailabilityIncrease(
        UUID ownerId, UUID facilityId, UUID locationId, String sku, int quantity, Instant occurredAt) {

    public StockAvailabilityIncrease {
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
