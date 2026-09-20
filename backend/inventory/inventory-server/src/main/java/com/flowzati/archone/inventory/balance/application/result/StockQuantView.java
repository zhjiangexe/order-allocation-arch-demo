package com.flowzati.archone.inventory.balance.application.result;

import java.time.LocalDate;
import java.util.UUID;

/** Immutable row projected for the operator-facing stock-by-location view. */
public record StockQuantView(
        UUID stockQuantId,
        String skuCode,
        LocalDate inDate,
        LocalDate expiryDate,
        int onHandQuantity,
        int reservedQuantity) {

    public StockQuantView {
        if (stockQuantId == null) {
            throw new IllegalArgumentException("Stock quant ID is required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (inDate == null) {
            throw new IllegalArgumentException("In-date is required");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (onHandQuantity < 0) {
            throw new IllegalArgumentException("On-hand quantity cannot be negative");
        }
        if (reservedQuantity < 0) {
            throw new IllegalArgumentException("Reserved quantity cannot be negative");
        }
        if (reservedQuantity > onHandQuantity) {
            throw new IllegalArgumentException("Reserved quantity cannot exceed on-hand quantity");
        }
    }

    public int availableToPromise() {
        return onHandQuantity - reservedQuantity;
    }

    /** The expiry date itself remains usable; a batch expires only after that business date. */
    public boolean isExpired(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("Today is required");
        }
        return expiryDate.isBefore(today);
    }
}
