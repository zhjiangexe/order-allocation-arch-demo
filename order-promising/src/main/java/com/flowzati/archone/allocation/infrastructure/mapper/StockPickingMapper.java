package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.infrastructure.entity.StockPickingEntity;
import java.util.UUID;

public final class StockPickingMapper {

  private StockPickingMapper() {
  }

  public static StockPickingEntity toEntity(StockPicking picking, UUID orderId) {
    return new StockPickingEntity(
        picking.id(), picking.pickingTypeId(), picking.ownerId(), orderId,
        picking.fromLocationId(), picking.toLocationId(),
        picking.reference(), picking.scheduledAt());
  }

  public static StockPicking toDomain(StockPickingEntity entity) {
    return new StockPicking(
        entity.getId(), entity.getPickingTypeId(), entity.getOwnerId(),
        entity.getFromLocationId(), entity.getToLocationId(),
        entity.getReference(), entity.getScheduledAt());
  }
}
