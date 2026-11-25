package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.model;

import com.flowzati.archone.inventory.movement.domain.enums.StockOperationCancellationState;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_operation_cancellations")
public class StockOperationCancellationEntity {

    @EmbeddedId
    private StockOperationCancellationPrimaryKey id;

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
        this.id = new StockOperationCancellationPrimaryKey(stockOperationId, cancellationOperationId);
        this.state = state;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getStockOperationId() {
        return id.getStockOperationId();
    }

    public UUID getCancellationOperationId() {
        return id.getCancellationOperationId();
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
