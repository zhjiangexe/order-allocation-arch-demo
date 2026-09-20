package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(StockOperationCancellationKey.class)
@Table(name = "stock_operation_cancellations")
public class StockOperationCancellationEntity {

    @Id
    @Column(name = "stock_operation_id")
    private UUID stockOperationId;

    @Id
    @Column(name = "cancellation_operation_id")
    private UUID cancellationOperationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockOperationCancellationState state;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StockOperationCancellationEntity() {}

    public StockOperationCancellationEntity(
            UUID stockOperationId,
            UUID cancellationOperationId,
            StockOperationCancellationState state,
            Instant startedAt,
            Instant updatedAt,
            Long version) {
        this.stockOperationId = stockOperationId;
        this.cancellationOperationId = cancellationOperationId;
        this.state = state;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getCancellationOperationId() {
        return cancellationOperationId;
    }

    public StockOperationCancellationState getState() {
        return state;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
