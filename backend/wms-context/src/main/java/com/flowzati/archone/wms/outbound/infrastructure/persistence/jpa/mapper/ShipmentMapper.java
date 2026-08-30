package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsPickTaskEntity;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsShipmentEntity;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsShipmentLineEntity;
import java.util.List;
import java.util.Objects;

/** Maps the complete Shipment aggregate graph to and from its JPA persistence model. */
public final class ShipmentMapper {

    private ShipmentMapper() {}

    public static Shipment toDomain(WmsShipmentEntity entity) {
        Objects.requireNonNull(entity, "Shipment entity is required");
        WarehouseWork work = entity.getWorkId() == null
                ? null
                : WarehouseWork.rehydrate(
                        entity.getWorkId(),
                        entity.getWorkWaveId(),
                        entity.getId(),
                        entity.getPickTasks().stream()
                                .map(ShipmentMapper::toDomain)
                                .toList(),
                        entity.getWorkStatus());
        return Shipment.rehydrate(
                entity.getId(),
                entity.getStockOperationId(),
                entity.getOrderId(),
                entity.getOwnerId(),
                entity.getFacilityId(),
                entity.getLines().stream().map(ShipmentMapper::toDomain).toList(),
                entity.getDispatchBy(),
                entity.getReleasePriority(),
                entity.getCreatedAt(),
                entity.getStatus(),
                entity.getWaveId(),
                work,
                entity.getCancellationState(),
                entity.getCancellationRequestId(),
                entity.getCancellationRequestedAt(),
                entity.getCancellationReason(),
                entity.getCancelledAt());
    }

    public static WmsShipmentEntity toEntity(Shipment shipment) {
        Objects.requireNonNull(shipment, "Shipment is required");
        WmsShipmentEntity entity = new WmsShipmentEntity(shipment.id());
        updateEntity(entity, shipment);
        return entity;
    }

    public static void updateEntity(WmsShipmentEntity entity, Shipment shipment) {
        Objects.requireNonNull(entity, "Shipment entity is required");
        Objects.requireNonNull(shipment, "Shipment is required");
        if (!entity.getId().equals(shipment.id())) {
            throw new IllegalArgumentException("Cannot map a different Shipment to an existing entity");
        }

        entity.setStockOperationId(shipment.stockOperationId());
        entity.setOrderId(shipment.orderId());
        entity.setOwnerId(shipment.ownerId());
        entity.setFacilityId(shipment.facilityId());
        entity.setCreatedAt(shipment.createdAt());
        entity.setDispatchBy(shipment.dispatchBy());
        entity.setReleasePriority(shipment.releasePriority());
        entity.setStatus(shipment.status());
        entity.setWaveId(shipment.waveId());
        entity.setCancellationState(shipment.cancellationStateValue().orElse(null));
        entity.setCancellationRequestId(shipment.cancellationRequestId());
        entity.setCancellationRequestedAt(shipment.cancellationRequestedAt());
        entity.setCancellationReason(shipment.cancellationReason());
        entity.setCancelledAt(shipment.cancelledAt());
        entity.replaceLines(
                shipment.lines().stream().map(ShipmentMapper::toEntity).toList());

        WarehouseWork work = shipment.pickingWork().orElse(null);
        if (work == null) {
            entity.setWorkId(null);
            entity.setWorkWaveId(null);
            entity.setWorkStatus(null);
            entity.replacePickTasks(List.of());
            return;
        }
        entity.setWorkId(work.id());
        entity.setWorkWaveId(work.waveId());
        entity.setWorkStatus(work.status());
        entity.replacePickTasks(
                work.pickTasks().stream().map(ShipmentMapper::toEntity).toList());
    }

    private static ShipmentLine toDomain(WmsShipmentLineEntity entity) {
        return new ShipmentLine(
                entity.getOrderLineId(),
                entity.getMoveId(),
                entity.getSkuCode(),
                entity.getSourceLocationId(),
                entity.getQuantity());
    }

    private static WmsShipmentLineEntity toEntity(ShipmentLine line) {
        return new WmsShipmentLineEntity(
                line.orderLineId(), line.moveId(), line.skuCode(), line.sourceLocationId(), line.quantity());
    }

    private static PickTask toDomain(WmsPickTaskEntity entity) {
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

    private static WmsPickTaskEntity toEntity(PickTask task) {
        return new WmsPickTaskEntity(
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
