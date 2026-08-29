package com.flowzati.archone.inventory.allocation.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Immutable eligible supply projected from one {@code StockQuant} row for pure allocation planning. */
public record StockQuantSupply(
        UUID stockQuantId,
        UUID ownerId,
        UUID locationId,
        String skuCode,
        LocalDate inDate,
        LocalDate expiryDate,
        int availableToPromise) {

    public StockQuantSupply {
        if (stockQuantId == null
                || ownerId == null
                || locationId == null
                || skuCode == null
                || skuCode.isBlank()
                || inDate == null
                || expiryDate == null
                || availableToPromise <= 0) {
            throw new IllegalArgumentException("Stock quant supply is invalid or not allocatable");
        }
    }
}
