package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wms_pick_tasks")
public class WmsPickTaskEntity {

    @Id
    private UUID id;

    @Column(name = "order_line_id", nullable = false)
    private UUID orderLineId;

    @Column(name = "move_id", nullable = false)
    private UUID moveId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "source_location_id", nullable = false)
    private UUID sourceLocationId;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(name = "picked_quantity", nullable = false)
    private int pickedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PickTaskStatus status;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected WmsPickTaskEntity() {}

    public WmsPickTaskEntity(
            UUID id,
            UUID orderLineId,
            UUID moveId,
            String skuCode,
            UUID sourceLocationId,
            int requestedQuantity,
            int pickedQuantity,
            PickTaskStatus status,
            Instant confirmedAt) {
        this.id = id;
        this.orderLineId = orderLineId;
        this.moveId = moveId;
        this.skuCode = skuCode;
        this.sourceLocationId = sourceLocationId;
        this.requestedQuantity = requestedQuantity;
        this.pickedQuantity = pickedQuantity;
        this.status = status;
        this.confirmedAt = confirmedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderLineId() {
        return orderLineId;
    }

    public UUID getMoveId() {
        return moveId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getPickedQuantity() {
        return pickedQuantity;
    }

    public PickTaskStatus getStatus() {
        return status;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
