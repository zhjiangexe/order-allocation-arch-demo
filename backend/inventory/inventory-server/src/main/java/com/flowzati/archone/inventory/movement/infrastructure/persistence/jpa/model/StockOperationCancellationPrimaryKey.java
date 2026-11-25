package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code stock_operation_cancellations} 的複合主鍵；以 {@code @EmbeddedId} 使用，主鍵欄位只在這裡定義一次。 */
@Embeddable
public class StockOperationCancellationPrimaryKey implements Serializable {

    @Column(name = "stock_operation_id", nullable = false)
    private UUID stockOperationId;

    @Column(name = "cancellation_operation_id", nullable = false)
    private UUID cancellationOperationId;

    protected StockOperationCancellationPrimaryKey() {}

    public StockOperationCancellationPrimaryKey(UUID stockOperationId, UUID cancellationOperationId) {
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
        return other instanceof StockOperationCancellationPrimaryKey that
                && Objects.equals(stockOperationId, that.stockOperationId)
                && Objects.equals(cancellationOperationId, that.cancellationOperationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stockOperationId, cancellationOperationId);
    }
}
