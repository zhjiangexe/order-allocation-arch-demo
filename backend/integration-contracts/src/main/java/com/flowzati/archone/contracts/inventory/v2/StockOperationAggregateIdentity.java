package com.flowzati.archone.contracts.inventory.v2;

import java.util.UUID;

/** Canonical read-side identity for current and retained legacy stock-operation aggregate references. */
public record StockOperationAggregateIdentity(UUID stockOperationId) {

    public StockOperationAggregateIdentity {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Stock operation ID is required");
        }
    }

    public static StockOperationAggregateIdentity from(String aggregateType, String aggregateId) {
        if (!InventoryAggregateTypes.representsStockOperation(aggregateType)) {
            throw new IllegalArgumentException("Unsupported stock operation aggregate type: " + aggregateType);
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("Stock operation aggregate ID is required");
        }
        return new StockOperationAggregateIdentity(UUID.fromString(aggregateId));
    }
}
