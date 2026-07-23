package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;

public final class StockPoolMapper {

  private StockPoolMapper() {
  }

  public static StockPoolEntity toEntity(StockPool stockPool) {
    return new StockPoolEntity(
        stockPool.getId(),
        stockPool.getSku(),
        stockPool.getOnHandQuantity(),
        stockPool.getReservedQuantity(),
        stockPool.getVersion()
    );
  }

  public static StockPool toDomain(StockPoolEntity stockPoolEntity) {
    return new StockPool(
        stockPoolEntity.getId(),
        stockPoolEntity.getSku(),
        stockPoolEntity.getOnHandQuantity(),
        stockPoolEntity.getReservedQuantity(),
        stockPoolEntity.getVersion()
    );
  }
}
