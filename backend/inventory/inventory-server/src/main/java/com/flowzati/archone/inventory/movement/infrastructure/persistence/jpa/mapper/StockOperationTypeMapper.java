package com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.entity.StockOperationTypeEntity;

public final class StockOperationTypeMapper {

    private StockOperationTypeMapper() {}

    public static StockOperationTypeEntity toEntity(StockOperationType type) {
        return new StockOperationTypeEntity(
                type.id(),
                type.facilityId(),
                type.code(),
                type.name(),
                type.defaultFromLocationId(),
                type.defaultToLocationId());
    }

    public static StockOperationType toDomain(StockOperationTypeEntity entity) {
        return new StockOperationType(
                entity.getId(),
                entity.getFacilityId(),
                entity.getCode(),
                entity.getName(),
                entity.getDefaultFromLocationId(),
                entity.getDefaultToLocationId());
    }
}
