package com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.entity;

import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveAssignment;
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

    WmsWaveAssignmentEntity(WaveAssignment assignment) {
        this.shipmentId = assignment.shipmentId();
        this.lineCount = assignment.lineCount();
        this.unitCount = assignment.unitCount();
        this.releasePriority = assignment.releasePriority();
        this.dispatchBy = assignment.dispatchBy();
    }

    WaveAssignment toDomain() {
        return new WaveAssignment(shipmentId, lineCount, unitCount, releasePriority, dispatchBy);
    }
}
