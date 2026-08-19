package com.flowzati.archone.wms.outbound.infrastructure.persistence.entity;

import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
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

    WmsPickTaskEntity(PickTask task) {
        this.id = task.id();
        this.orderLineId = task.orderLineId();
        this.moveId = task.moveId();
        this.skuCode = task.skuCode();
        this.sourceLocationId = task.sourceLocationId();
        this.requestedQuantity = task.requestedQuantity();
        this.pickedQuantity = task.pickedQuantity();
        this.status = task.status();
        this.confirmedAt = task.confirmedAt();
    }

    PickTask toDomain() {
        return PickTask.rehydrate(
                id,
                orderLineId,
                moveId,
                skuCode,
                sourceLocationId,
                requestedQuantity,
                pickedQuantity,
                status,
                confirmedAt);
    }
}
