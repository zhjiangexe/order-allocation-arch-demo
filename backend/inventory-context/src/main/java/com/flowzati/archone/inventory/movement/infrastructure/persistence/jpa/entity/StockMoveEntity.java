package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity;

import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_moves")
public class StockMoveEntity {

    @Id
    private UUID id;

    @Column(name = "stock_operation_id")
    private UUID stockOperationId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "from_location_id", nullable = false)
    private UUID fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    @Column(name = "source_line_id")
    private String sourceLineId;

    @Column(name = "line_sequence")
    private Integer lineSequence;

    @Column(name = "demand_quantity", nullable = false)
    private int demandQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MoveState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Version
    private Long version;

    protected StockMoveEntity() {}

    public StockMoveEntity(
            UUID id,
            UUID stockOperationId,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            String sourceLineId,
            Integer lineSequence,
            int demandQuantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt,
            Long version) {
        this.id = id;
        this.stockOperationId = stockOperationId;
        this.ownerId = ownerId;
        this.skuCode = skuCode;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.sourceLineId = sourceLineId;
        this.lineSequence = lineSequence;
        this.demandQuantity = demandQuantity;
        this.state = state;
        this.createdAt = createdAt;
        this.assignedAt = assignedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public UUID getFromLocationId() {
        return fromLocationId;
    }

    public UUID getToLocationId() {
        return toLocationId;
    }

    public String getSourceLineId() {
        return sourceLineId;
    }

    public Integer getLineSequence() {
        return lineSequence;
    }

    public int getDemandQuantity() {
        return demandQuantity;
    }

    public MoveState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Long getVersion() {
        return version;
    }
}
