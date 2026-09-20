package com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.wave.domain.valueobject.WavePlanningPolicy;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.model.WaveAssignmentEntity;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.model.WaveEntity;
import java.util.Objects;

/** Maps the complete Wave aggregate graph to and from its JPA persistence model. */
public final class WaveMapper {

    private WaveMapper() {}

    public static Wave toDomain(WaveEntity entity) {
        Objects.requireNonNull(entity, "Wave entity is required");
        return Wave.rehydrate(
                entity.getId(),
                entity.getFacilityId(),
                entity.getTemplateCode(),
                new WavePlanningPolicy(
                        entity.getFacilityId(),
                        entity.getDispatchByCutoff(),
                        entity.getMaxShipments(),
                        entity.getMaxLines(),
                        entity.getMaxUnits()),
                entity.getAssignments().stream().map(WaveMapper::toDomain).toList(),
                entity.getPlannedAt(),
                entity.getStatus(),
                entity.getPickingWorkCount(),
                entity.getPickTaskCount());
    }

    public static WaveEntity toEntity(Wave wave) {
        Objects.requireNonNull(wave, "Wave is required");
        WaveEntity entity = new WaveEntity(wave.id());
        updateEntity(entity, wave);
        return entity;
    }

    public static void updateEntity(WaveEntity entity, Wave wave) {
        Objects.requireNonNull(entity, "Wave entity is required");
        Objects.requireNonNull(wave, "Wave is required");
        if (!entity.getId().equals(wave.id())) {
            throw new IllegalArgumentException("Cannot map a different Wave to an existing entity");
        }

        entity.setFacilityId(wave.facilityId());
        entity.setTemplateCode(wave.templateCode());
        entity.setDispatchByCutoff(wave.dispatchByCutoff());
        entity.setMaxShipments(wave.maxShipments());
        entity.setMaxLines(wave.maxLines());
        entity.setMaxUnits(wave.maxUnits());
        entity.setPlannedAt(wave.plannedAt());
        entity.setStatus(wave.status());
        entity.setPickingWorkCount(wave.pickingWorkCount());
        entity.setPickTaskCount(wave.pickTaskCount());
        entity.replaceAssignments(
                wave.assignments().stream().map(WaveMapper::toEntity).toList());
    }

    private static WaveAssignment toDomain(WaveAssignmentEntity entity) {
        return new WaveAssignment(
                entity.getShipmentId(),
                entity.getLineCount(),
                entity.getUnitCount(),
                entity.getReleasePriority(),
                entity.getDispatchBy());
    }

    private static WaveAssignmentEntity toEntity(WaveAssignment assignment) {
        return new WaveAssignmentEntity(
                assignment.shipmentId(),
                assignment.lineCount(),
                assignment.unitCount(),
                assignment.releasePriority(),
                assignment.dispatchBy());
    }
}
