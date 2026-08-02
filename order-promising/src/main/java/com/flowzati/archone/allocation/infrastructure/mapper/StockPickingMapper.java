package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.infrastructure.entity.StockPickingEntity;

public final class StockPickingMapper {

  private StockPickingMapper() {
  }

  public static StockPickingEntity toEntity(StockPicking picking) {
    return new StockPickingEntity(
        picking.id(), picking.pickingTypeId(), picking.ownerId(), picking.orderId(),
        picking.fromLocationId(), picking.toLocationId());
  }

  public static StockPicking toDomain(StockPickingEntity entity) {
    return new StockPicking(
        entity.getId(), entity.getPickingTypeId(), entity.getOwnerId(), entity.getOrderId(),
        entity.getFromLocationId(), entity.getToLocationId());
  }
}
