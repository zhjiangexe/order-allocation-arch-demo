package com.flowzati.archone.inventory.warehouse.infrastructure.mapper;

import com.flowzati.archone.inventory.warehouse.domain.aggregate.PickingType;
import com.flowzati.archone.inventory.warehouse.infrastructure.entity.PickingTypeEntity;

public final class PickingTypeMapper {

    private PickingTypeMapper() {}

    public static PickingTypeEntity toEntity(PickingType type) {
        return new PickingTypeEntity(
                type.id(),
                type.facilityId(),
                type.code(),
                type.name(),
                type.defaultFromLocationId(),
                type.defaultToLocationId());
    }

    public static PickingType toDomain(PickingTypeEntity entity) {
        return new PickingType(
                entity.getId(),
                entity.getFacilityId(),
                entity.getCode(),
                entity.getName(),
                entity.getDefaultFromLocationId(),
                entity.getDefaultToLocationId());
    }
}
