package com.flowzati.archone.inventory.movement.infrastructure.entity;

import com.flowzati.archone.inventory.movement.domain.type.MoveState;
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

    @Column(name = "picking_id")
    private UUID pickingId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "from_location_id", nullable = false)
    private UUID fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    /** 需求與執行之間唯一的連結。入庫時為空。 */
    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "allocation_demand_id")
    private UUID allocationDemandId;

    @Column(name = "allocation_demand_line_id")
    private UUID allocationDemandLineId;

    @Column(name = "source_line_id")
    private String sourceLineId;

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
            UUID pickingId,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            UUID allocationDemandId,
            UUID allocationDemandLineId,
            String sourceLineId,
            UUID orderLineId,
            int demandQuantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt,
            Long version) {
        this.id = id;
        this.pickingId = pickingId;
        this.ownerId = ownerId;
        this.skuCode = skuCode;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.allocationDemandId = allocationDemandId;
        this.allocationDemandLineId = allocationDemandLineId;
        this.sourceLineId = sourceLineId;
        this.orderLineId = orderLineId;
        this.demandQuantity = demandQuantity;
        this.state = state;
        this.createdAt = createdAt;
        this.assignedAt = assignedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPickingId() {
        return pickingId;
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

    public UUID getOrderLineId() {
        return orderLineId;
    }

    public UUID getAllocationDemandId() {
        return allocationDemandId;
    }

    public UUID getAllocationDemandLineId() {
        return allocationDemandLineId;
    }

    public String getSourceLineId() {
        return sourceLineId;
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
