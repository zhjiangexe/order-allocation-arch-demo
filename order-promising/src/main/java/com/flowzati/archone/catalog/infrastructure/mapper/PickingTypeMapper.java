package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.model.PickingType;
import com.flowzati.archone.catalog.infrastructure.entity.PickingTypeEntity;

public final class PickingTypeMapper {

  private PickingTypeMapper() {
  }

  public static PickingTypeEntity toEntity(PickingType type) {
    return new PickingTypeEntity(
        type.id(), type.warehouseId(), type.code(), type.name(),
        type.defaultFromLocationId(), type.defaultToLocationId());
  }

  public static PickingType toDomain(PickingTypeEntity entity) {
    return new PickingType(
        entity.getId(), entity.getWarehouseId(), entity.getCode(), entity.getName(),
        entity.getDefaultFromLocationId(), entity.getDefaultToLocationId());
  }
}
