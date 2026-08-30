package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.outbound.domain.type.WaveStatus;
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

@Entity
@Table(name = "wms_waves")
public class WmsWaveEntity {

    @Id
    private UUID id;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "template_code", nullable = false)
    private String templateCode;

    @Column(name = "dispatch_by_cutoff", nullable = false)
    private Instant dispatchByCutoff;

    @Column(name = "max_shipments", nullable = false)
    private int maxShipments;

    @Column(name = "max_lines", nullable = false)
    private int maxLines;

    @Column(name = "max_units", nullable = false)
    private int maxUnits;

    @Column(name = "planned_at", nullable = false)
    private Instant plannedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaveStatus status;

    @Column(name = "warehouse_work_count", nullable = false)
    private int warehouseWorkCount;

    @Column(name = "pick_task_count", nullable = false)
    private int pickTaskCount;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "wave_id", nullable = false)
    @OrderBy("shipmentId ASC")
    private List<WmsWaveAssignmentEntity> assignments = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    protected WmsWaveEntity() {}

    public WmsWaveEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "Wave ID is required");
    }

    public UUID getId() {
        return id;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public void setFacilityId(UUID facilityId) {
        this.facilityId = facilityId;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public Instant getDispatchByCutoff() {
        return dispatchByCutoff;
    }

    public void setDispatchByCutoff(Instant dispatchByCutoff) {
        this.dispatchByCutoff = dispatchByCutoff;
    }

    public int getMaxShipments() {
        return maxShipments;
    }

    public void setMaxShipments(int maxShipments) {
        this.maxShipments = maxShipments;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getMaxUnits() {
        return maxUnits;
    }

    public void setMaxUnits(int maxUnits) {
        this.maxUnits = maxUnits;
    }

    public Instant getPlannedAt() {
        return plannedAt;
    }

    public void setPlannedAt(Instant plannedAt) {
        this.plannedAt = plannedAt;
    }

    public WaveStatus getStatus() {
        return status;
    }

    public void setStatus(WaveStatus status) {
        this.status = status;
    }

    public int getWarehouseWorkCount() {
        return warehouseWorkCount;
    }

    public void setWarehouseWorkCount(int warehouseWorkCount) {
        this.warehouseWorkCount = warehouseWorkCount;
    }

    public int getPickTaskCount() {
        return pickTaskCount;
    }

    public void setPickTaskCount(int pickTaskCount) {
        this.pickTaskCount = pickTaskCount;
    }

    public List<WmsWaveAssignmentEntity> getAssignments() {
        return List.copyOf(assignments);
    }

    public void replaceAssignments(List<WmsWaveAssignmentEntity> assignments) {
        this.assignments.clear();
        this.assignments.addAll(List.copyOf(Objects.requireNonNull(assignments, "Wave assignments are required")));
    }
}
