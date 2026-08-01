package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.infrastructure.entity.StockLocationEntity;

public final class StockLocationMapper {

  private StockLocationMapper() {
  }

  public static StockLocationEntity toEntity(StockLocation location) {
    return new StockLocationEntity(
        location.getId(),
        location.getWarehouseId(),
        location.getCode(),
        location.getName(),
        location.getUsage());
  }

  public static StockLocation toDomain(StockLocationEntity entity) {
    return new StockLocation(
        entity.getId(),
        entity.getWarehouseId(),
        entity.getCode(),
        entity.getName(),
        entity.getUsage());
  }
}
