package com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.model.ShipmentDispatchEntity;

public final class ShipmentDispatchMapper {

    private ShipmentDispatchMapper() {}

    public static ShipmentDispatch toDomain(ShipmentDispatchEntity entity) {
        return ShipmentDispatch.rehydrate(
                entity.getId(),
                entity.getShipmentId(),
                entity.getPackedAt(),
                entity.getStatus(),
                entity.getStagedAt(),
                entity.getHandedOverAt());
    }

    public static ShipmentDispatchEntity toEntity(ShipmentDispatch shipmentDispatch) {
        ShipmentDispatchEntity entity = new ShipmentDispatchEntity(shipmentDispatch.id());
        updateEntity(entity, shipmentDispatch);
        return entity;
    }

    public static void updateEntity(ShipmentDispatchEntity entity, ShipmentDispatch shipmentDispatch) {
        if (!entity.getId().equals(shipmentDispatch.id())) {
            throw new IllegalArgumentException("Cannot map a different ShipmentDispatch to an existing entity");
        }
        entity.setShipmentId(shipmentDispatch.shipmentId());
        entity.setStatus(shipmentDispatch.status());
        entity.setPackedAt(shipmentDispatch.packedAt());
        entity.setStagedAt(shipmentDispatch.stagedAt());
        entity.setHandedOverAt(shipmentDispatch.handedOverAt());
    }
}
