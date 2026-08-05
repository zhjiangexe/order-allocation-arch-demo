package com.flowzati.archone.stock.infrastructure.mapper;

import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.infrastructure.entity.StockPickingEntity;

public final class StockPickingMapper {

  private StockPickingMapper() {
  }

  public static StockPickingEntity toEntity(StockPicking picking) {
    return new StockPickingEntity(
        picking.id(), picking.pickingTypeId(), picking.ownerId(), picking.orderId(),
        picking.fromLocationId(), picking.toLocationId(), picking.state(), picking.version());
  }

  public static StockPicking toDomain(StockPickingEntity entity) {
    return new StockPicking(
        entity.getId(), entity.getPickingTypeId(), entity.getOwnerId(), entity.getOrderId(),
        entity.getFromLocationId(), entity.getToLocationId(), entity.getState(),
        entity.getVersion());
  }
}
