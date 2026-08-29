package com.flowzati.archone.inventory.movement.infrastructure.entity;

import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Inventory movement operation group 的 persistence shape；不是 WMS Shipment 或 task。
 *
 * <p>{@code state} 是底下 moves 的物化摘要，與 moves 在同一 transaction 更新。配貨與取消
 * 可能並發，因此用 {@code version} 防止最後寫入者覆蓋另一條流程。
 */
@Entity
@Table(name = "stock_operations")
public class StockOperationEntity {

    @Id
    private UUID id;

    @Column(name = "stock_operation_type_id", nullable = false)
    private UUID stockOperationTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction")
    private StockOperationDirection direction;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private MovementSourceType sourceType;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "allocation_unit_key")
    private String allocationUnitKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_code")
    private MovementAssignmentPolicy assignmentPolicy;

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    @Column(name = "from_location_id", nullable = false)
    private UUID fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    @Column(name = "dispatch_by")
    private Instant dispatchBy;

    @Column(name = "release_priority")
    private Integer releasePriority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockOperationState state;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StockOperationEntity() {}

    public StockOperationEntity(
            UUID id,
            UUID stockOperationTypeId,
            StockOperationDirection direction,
            UUID ownerId,
            MovementSourceType sourceType,
            String sourceId,
            String allocationUnitKey,
            MovementAssignmentPolicy assignmentPolicy,
            Instant enqueuedAt,
            UUID fromLocationId,
            UUID toLocationId,
            Instant dispatchBy,
            Integer releasePriority,
            StockOperationState state,
            Long version) {
        this.id = id;
        this.stockOperationTypeId = stockOperationTypeId;
        this.direction = direction;
        this.ownerId = ownerId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.allocationUnitKey = allocationUnitKey;
        this.assignmentPolicy = assignmentPolicy;
        this.enqueuedAt = enqueuedAt;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.dispatchBy = dispatchBy;
        this.releasePriority = releasePriority;
        this.state = state;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockOperationTypeId() {
        return stockOperationTypeId;
    }

    public StockOperationDirection getDirection() {
        return direction;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public MovementSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAllocationUnitKey() {
        return allocationUnitKey;
    }

    public MovementAssignmentPolicy getAssignmentPolicy() {
        return assignmentPolicy;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public UUID getFromLocationId() {
        return fromLocationId;
    }

    public UUID getToLocationId() {
        return toLocationId;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }

    public Integer getReleasePriority() {
        return releasePriority;
    }

    public StockOperationState getState() {
        return state;
    }

    public Long getVersion() {
        return version;
    }
}
