package com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.entity;

import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.policy.WavePlanningPolicy;
import com.flowzati.archone.wms.outbound.wave.domain.type.WaveStatus;
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

    public WmsWaveEntity(Wave wave) {
        this.id = wave.id();
        replaceFrom(wave);
    }

    public void replaceFrom(Wave wave) {
        if (id != null && !id.equals(wave.id())) {
            throw new IllegalArgumentException("Cannot replace a different Wave entity");
        }
        this.id = wave.id();
        this.facilityId = wave.facilityId();
        this.templateCode = wave.templateCode();
        this.dispatchByCutoff = wave.dispatchByCutoff();
        this.maxShipments = wave.maxShipments();
        this.maxLines = wave.maxLines();
        this.maxUnits = wave.maxUnits();
        this.plannedAt = wave.plannedAt();
        this.status = wave.status();
        this.warehouseWorkCount = wave.warehouseWorkCount();
        this.pickTaskCount = wave.pickTaskCount();
        this.assignments.clear();
        wave.assignments().stream().map(WmsWaveAssignmentEntity::new).forEach(this.assignments::add);
    }

    public Wave toDomain() {
        return Wave.rehydrate(
                id,
                facilityId,
                templateCode,
                new WavePlanningPolicy(facilityId, dispatchByCutoff, maxShipments, maxLines, maxUnits),
                assignments.stream().map(WmsWaveAssignmentEntity::toDomain).toList(),
                plannedAt,
                status,
                warehouseWorkCount,
                pickTaskCount);
    }
}
