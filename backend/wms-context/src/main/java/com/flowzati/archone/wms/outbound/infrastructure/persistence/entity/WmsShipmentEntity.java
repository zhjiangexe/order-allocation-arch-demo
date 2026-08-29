package com.flowzati.archone.wms.outbound.infrastructure.persistence.entity;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationState;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.type.WarehouseWorkStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA persistence shape for one complete Shipment aggregate. */
@Entity
@Table(name = "wms_shipments")
public class WmsShipmentEntity {

    @Id
    private UUID id;

    @Column(name = "stock_operation_id", nullable = false, unique = true)
    private UUID stockOperationId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dispatch_by", nullable = false)
    private Instant dispatchBy;

    @Column(name = "release_priority", nullable = false)
    private int releasePriority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "wave_id")
    private UUID waveId;

    @Column(name = "work_id")
    private UUID workId;

    @Column(name = "work_wave_id")
    private UUID workWaveId;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_status")
    private WarehouseWorkStatus workStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_state")
    private ShipmentCancellationState cancellationState;

    @Column(name = "cancellation_request_id")
    private UUID cancellationRequestId;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_reason", length = 512)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    @OrderBy("orderLineId ASC")
    private List<WmsShipmentLineEntity> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    @OrderBy("id ASC")
    private List<WmsPickTaskEntity> pickTasks = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    protected WmsShipmentEntity() {}

    public WmsShipmentEntity(Shipment shipment) {
        this.id = shipment.id();
        replaceFrom(shipment);
    }

    public void replaceFrom(Shipment shipment) {
        if (id != null && !id.equals(shipment.id())) {
            throw new IllegalArgumentException("Cannot replace a different Shipment entity");
        }
        this.id = shipment.id();
        this.stockOperationId = shipment.stockOperationId();
        this.orderId = shipment.orderId();
        this.ownerId = shipment.ownerId();
        this.facilityId = shipment.facilityId();
        this.createdAt = shipment.createdAt();
        this.dispatchBy = shipment.dispatchBy();
        this.releasePriority = shipment.releasePriority();
        this.status = shipment.status();
        this.waveId = shipment.waveId();
        this.cancellationState = shipment.cancellationStateValue().orElse(null);
        this.cancellationRequestId = shipment.cancellationRequestId();
        this.cancellationRequestedAt = shipment.cancellationRequestedAt();
        this.cancellationReason = shipment.cancellationReason();
        this.cancelledAt = shipment.cancelledAt();

        this.lines.clear();
        shipment.lines().stream().map(WmsShipmentLineEntity::new).forEach(this.lines::add);
        this.pickTasks.clear();
        WarehouseWork work = shipment.pickingWork().orElse(null);
        if (work == null) {
            this.workId = null;
            this.workWaveId = null;
            this.workStatus = null;
        } else {
            this.workId = work.id();
            this.workWaveId = work.waveId();
            this.workStatus = work.status();
            work.pickTasks().stream().map(WmsPickTaskEntity::new).forEach(this.pickTasks::add);
        }
    }

    public Shipment toDomain() {
        WarehouseWork work = workId == null
                ? null
                : WarehouseWork.rehydrate(
                        workId,
                        workWaveId,
                        id,
                        pickTasks.stream().map(WmsPickTaskEntity::toDomain).toList(),
                        workStatus);
        return Shipment.rehydrate(
                id,
                stockOperationId,
                orderId,
                ownerId,
                facilityId,
                lines.stream().map(WmsShipmentLineEntity::toDomain).toList(),
                dispatchBy,
                releasePriority,
                createdAt,
                status,
                waveId,
                work,
                cancellationState,
                cancellationRequestId,
                cancellationRequestedAt,
                cancellationReason,
                cancelledAt);
    }

    public UUID getId() {
        return id;
    }
}
