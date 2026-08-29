package com.flowzati.archone.inventory.movement.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class StockOperationCancellationKey implements Serializable {

    private UUID stockOperationId;
    private UUID cancellationOperationId;

    public StockOperationCancellationKey() {}

    public StockOperationCancellationKey(UUID stockOperationId, UUID cancellationOperationId) {
        this.stockOperationId = stockOperationId;
        this.cancellationOperationId = cancellationOperationId;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getCancellationOperationId() {
        return cancellationOperationId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StockOperationCancellationKey that
                && Objects.equals(stockOperationId, that.stockOperationId)
                && Objects.equals(cancellationOperationId, that.cancellationOperationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stockOperationId, cancellationOperationId);
    }
}
