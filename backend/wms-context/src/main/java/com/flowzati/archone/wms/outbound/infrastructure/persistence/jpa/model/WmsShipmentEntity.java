package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model;

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
import java.util.Objects;
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

    public WmsShipmentEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "Shipment ID is required");
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public void setStockOperationId(UUID stockOperationId) {
        this.stockOperationId = stockOperationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public void setFacilityId(UUID facilityId) {
        this.facilityId = facilityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }

    public void setDispatchBy(Instant dispatchBy) {
        this.dispatchBy = dispatchBy;
    }

    public int getReleasePriority() {
        return releasePriority;
    }

    public void setReleasePriority(int releasePriority) {
        this.releasePriority = releasePriority;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public void setStatus(ShipmentStatus status) {
        this.status = status;
    }

    public UUID getWaveId() {
        return waveId;
    }

    public void setWaveId(UUID waveId) {
        this.waveId = waveId;
    }

    public UUID getWorkId() {
        return workId;
    }

    public void setWorkId(UUID workId) {
        this.workId = workId;
    }

    public UUID getWorkWaveId() {
        return workWaveId;
    }

    public void setWorkWaveId(UUID workWaveId) {
        this.workWaveId = workWaveId;
    }

    public WarehouseWorkStatus getWorkStatus() {
        return workStatus;
    }

    public void setWorkStatus(WarehouseWorkStatus workStatus) {
        this.workStatus = workStatus;
    }

    public ShipmentCancellationState getCancellationState() {
        return cancellationState;
    }

    public void setCancellationState(ShipmentCancellationState cancellationState) {
        this.cancellationState = cancellationState;
    }

    public UUID getCancellationRequestId() {
        return cancellationRequestId;
    }

    public void setCancellationRequestId(UUID cancellationRequestId) {
        this.cancellationRequestId = cancellationRequestId;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public void setCancellationRequestedAt(Instant cancellationRequestedAt) {
        this.cancellationRequestedAt = cancellationRequestedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public List<WmsShipmentLineEntity> getLines() {
        return List.copyOf(lines);
    }

    public void replaceLines(List<WmsShipmentLineEntity> lines) {
        this.lines.clear();
        this.lines.addAll(List.copyOf(Objects.requireNonNull(lines, "Shipment lines are required")));
    }

    public List<WmsPickTaskEntity> getPickTasks() {
        return List.copyOf(pickTasks);
    }

    public void replacePickTasks(List<WmsPickTaskEntity> pickTasks) {
        this.pickTasks.clear();
        this.pickTasks.addAll(List.copyOf(Objects.requireNonNull(pickTasks, "Pick tasks are required")));
    }
}
