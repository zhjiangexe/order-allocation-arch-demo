package com.flowzati.archone.inventory.location.infrastructure.mapper;

import com.flowzati.archone.inventory.location.domain.StockLocation;
import com.flowzati.archone.inventory.location.infrastructure.entity.StockLocationEntity;

public final class StockLocationMapper {

    private StockLocationMapper() {}

    public static StockLocationEntity toEntity(StockLocation location) {
        return new StockLocationEntity(
                location.getId(),
                location.getFacilityId(),
                location.getCode(),
                location.getName(),
                location.getUsage());
    }

    public static StockLocation toDomain(StockLocationEntity entity) {
        return new StockLocation(
                entity.getId(), entity.getFacilityId(), entity.getCode(), entity.getName(), entity.getUsage());
    }
}
