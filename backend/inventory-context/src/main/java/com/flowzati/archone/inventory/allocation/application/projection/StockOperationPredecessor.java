package com.flowzati.archone.inventory.allocation.application.projection;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** The earliest confirmed operation that shares at least one contended SKU with a candidate. */
public record StockOperationPredecessor(UUID stockOperationId, Instant enqueuedAt, Set<String> sharedSkuCodes) {

    public StockOperationPredecessor {
        if (stockOperationId == null || enqueuedAt == null || sharedSkuCodes == null || sharedSkuCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Stock operation predecessor requires identity, enqueue time and shared SKUs");
        }
        sharedSkuCodes = Set.copyOf(sharedSkuCodes);
    }
}
