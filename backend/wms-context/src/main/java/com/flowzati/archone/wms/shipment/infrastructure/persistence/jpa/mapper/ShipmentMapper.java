package com.flowzati.archone.wms.shipment.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.valueobject.ShipmentLine;
import com.flowzati.archone.wms.shipment.infrastructure.persistence.jpa.model.ShipmentEntity;
import com.flowzati.archone.wms.shipment.infrastructure.persistence.jpa.model.ShipmentLineEntity;
import java.util.Objects;

/** Maps the complete Shipment aggregate graph to and from its JPA persistence model. */
public final class ShipmentMapper {

    private ShipmentMapper() {}

    public static Shipment toDomain(ShipmentEntity entity) {
        Objects.requireNonNull(entity, "Shipment entity is required");
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
                entity.getCancellationState(),
                entity.getCancellationRequestId(),
                entity.getCancellationRequestedAt(),
                entity.getCancellationReason(),
                entity.getCancelledAt());
    }

    public static ShipmentEntity toEntity(Shipment shipment) {
        Objects.requireNonNull(shipment, "Shipment is required");
        ShipmentEntity entity = new ShipmentEntity(shipment.id());
        updateEntity(entity, shipment);
        return entity;
    }

    public static void updateEntity(ShipmentEntity entity, Shipment shipment) {
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
    }

    private static ShipmentLine toDomain(ShipmentLineEntity entity) {
        return new ShipmentLine(
                entity.getOrderLineId(),
                entity.getMoveId(),
                entity.getSkuCode(),
                entity.getSourceLocationId(),
                entity.getQuantity());
    }

    private static ShipmentLineEntity toEntity(ShipmentLine line) {
        return new ShipmentLineEntity(
                line.orderLineId(), line.moveId(), line.skuCode(), line.sourceLocationId(), line.quantity());
    }
}
