package com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.picking.domain.type.PickingWorkStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wms_picking_works")
public class PickingWorkEntity {

    @Id
    private UUID id;

    @Column(name = "wave_id", nullable = false)
    private UUID waveId;

    @Column(name = "shipment_id", nullable = false, unique = true)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PickingWorkStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    @OrderBy("id ASC")
    private List<PickTaskEntity> pickTasks = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    protected PickingWorkEntity() {}

    public PickingWorkEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "PickingWork ID is required");
    }

    public UUID getId() {
        return id;
    }

    public UUID getWaveId() {
        return waveId;
    }

    public void setWaveId(UUID waveId) {
        this.waveId = waveId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(UUID shipmentId) {
        this.shipmentId = shipmentId;
    }

    public PickingWorkStatus getStatus() {
        return status;
    }

    public void setStatus(PickingWorkStatus status) {
        this.status = status;
    }

    public List<PickTaskEntity> getPickTasks() {
        return List.copyOf(pickTasks);
    }

    public void replacePickTasks(List<PickTaskEntity> pickTasks) {
        this.pickTasks.clear();
        this.pickTasks.addAll(pickTasks);
    }
}
