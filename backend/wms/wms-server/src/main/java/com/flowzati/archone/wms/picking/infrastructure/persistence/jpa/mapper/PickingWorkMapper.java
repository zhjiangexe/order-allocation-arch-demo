package com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.domain.entity.PickTask;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.model.PickTaskEntity;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.model.PickingWorkEntity;
import java.util.Objects;

public final class PickingWorkMapper {

    private PickingWorkMapper() {}

    public static PickingWork toDomain(PickingWorkEntity entity) {
        Objects.requireNonNull(entity, "PickingWork entity is required");
        return PickingWork.rehydrate(
                entity.getId(),
                entity.getWaveId(),
                entity.getShipmentId(),
                entity.getPickTasks().stream().map(PickingWorkMapper::toDomain).toList(),
                entity.getStatus());
    }

    public static PickingWorkEntity toEntity(PickingWork work) {
        PickingWorkEntity entity = new PickingWorkEntity(work.id());
        updateEntity(entity, work);
        return entity;
    }

    public static void updateEntity(PickingWorkEntity entity, PickingWork work) {
        if (!entity.getId().equals(work.id())) {
            throw new IllegalArgumentException("Cannot map a different PickingWork to an existing entity");
        }
        entity.setWaveId(work.waveId());
        entity.setShipmentId(work.shipmentId());
        entity.setStatus(work.status());
        entity.replacePickTasks(
                work.pickTasks().stream().map(PickingWorkMapper::toEntity).toList());
    }

    private static PickTask toDomain(PickTaskEntity entity) {
        return PickTask.rehydrate(
                entity.getId(),
                entity.getOrderLineId(),
                entity.getMoveId(),
                entity.getSkuCode(),
                entity.getSourceLocationId(),
                entity.getRequestedQuantity(),
                entity.getPickedQuantity(),
                entity.getStatus(),
                entity.getConfirmedAt());
    }

    private static PickTaskEntity toEntity(PickTask task) {
        return new PickTaskEntity(
                task.id(),
                task.orderLineId(),
                task.moveId(),
                task.skuCode(),
                task.sourceLocationId(),
                task.requestedQuantity(),
                task.pickedQuantity(),
                task.status(),
                task.confirmedAt());
    }
}
