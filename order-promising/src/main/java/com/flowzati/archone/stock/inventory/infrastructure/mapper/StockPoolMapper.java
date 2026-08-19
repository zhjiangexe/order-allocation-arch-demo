package com.flowzati.archone.stock.inventory.infrastructure.mapper;

import com.flowzati.archone.stock.inventory.domain.aggregate.StockPool;
import com.flowzati.archone.stock.inventory.infrastructure.entity.StockPoolEntity;

public final class StockPoolMapper {

  private StockPoolMapper() {
  }

  public static StockPoolEntity toEntity(StockPool stockPool) {
    return new StockPoolEntity(
        stockPool.getId(),
        stockPool.getOwnerId(),
        stockPool.getLocationId(),
        stockPool.getSkuCode(),
        stockPool.getInDate(),
        stockPool.getExpiryDate(),
        stockPool.getOnHandQuantity(),
        stockPool.getReservedQuantity(),
        stockPool.getVersion()
    );
  }

  public static StockPool toDomain(StockPoolEntity entity) {
    return new StockPool(
        entity.getId(),
        entity.getOwnerId(),
        entity.getLocationId(),
        entity.getSkuCode(),
        entity.getInDate(),
        entity.getExpiryDate(),
        entity.getOnHandQuantity(),
        entity.getReservedQuantity(),
        entity.getVersion()
    );
  }
}
