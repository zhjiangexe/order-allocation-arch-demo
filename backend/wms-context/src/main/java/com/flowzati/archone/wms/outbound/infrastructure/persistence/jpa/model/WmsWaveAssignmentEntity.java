package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wms_wave_assignments")
public class WmsWaveAssignmentEntity {

    @Id
    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "unit_count", nullable = false)
    private int unitCount;

    @Column(name = "release_priority", nullable = false)
    private int releasePriority;

    @Column(name = "dispatch_by", nullable = false)
    private Instant dispatchBy;

    protected WmsWaveAssignmentEntity() {}

    public WmsWaveAssignmentEntity(
            UUID shipmentId, int lineCount, int unitCount, int releasePriority, Instant dispatchBy) {
        this.shipmentId = shipmentId;
        this.lineCount = lineCount;
        this.unitCount = unitCount;
        this.releasePriority = releasePriority;
        this.dispatchBy = dispatchBy;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public int getLineCount() {
        return lineCount;
    }

    public int getUnitCount() {
        return unitCount;
    }

    public int getReleasePriority() {
        return releasePriority;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }
}
