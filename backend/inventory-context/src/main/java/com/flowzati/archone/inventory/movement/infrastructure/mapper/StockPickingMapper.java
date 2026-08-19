package com.flowzati.archone.inventory.movement.infrastructure.mapper;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.infrastructure.entity.StockPickingEntity;

public final class StockPickingMapper {

    private StockPickingMapper() {}

    public static StockPickingEntity toEntity(StockPicking picking) {
        return new StockPickingEntity(
                picking.id(),
                picking.pickingTypeId(),
                picking.direction(),
                picking.ownerId(),
                picking.orderId(),
                picking.fromLocationId(),
                picking.toLocationId(),
                picking.dispatchBy(),
                picking.releasePriority(),
                picking.state(),
                picking.version());
    }

    public static StockPicking toDomain(StockPickingEntity entity) {
        return new StockPicking(
                entity.getId(),
                entity.getPickingTypeId(),
                entity.getDirection() == null
                        ? (entity.getDispatchBy() == null
                                ? com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection.INBOUND
                                : com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection.OUTBOUND)
                        : entity.getDirection(),
                entity.getOwnerId(),
                entity.getOrderId(),
                entity.getFromLocationId(),
                entity.getToLocationId(),
                entity.getDispatchBy(),
                entity.getReleasePriority(),
                entity.getState(),
                entity.getVersion());
    }
}
