package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;

import java.lang.reflect.Field;

public class StockPoolMapper {
  public static StockPoolEntity toEntity(StockPool stockPool) {
    StockPoolEntity entity = new StockPoolEntity();
    // 使用反射來設定私有欄位，或者建議在 StockEntity 增加 setter/constructor
    try {
      setField(entity, "id", stockPool.getId());
      setField(entity, "sku", stockPool.getSku());
      setField(entity, "available", stockPool.getAvailable());
      setField(entity, "version", stockPool.getVersion());
    } catch (Exception e) {
      throw new RuntimeException("Mapping failed", e);
    }
    return entity;
  }

  public static StockPool toDomain(StockPoolEntity stockPoolEntity) {
    return new StockPool(
            stockPoolEntity.getId(),
            stockPoolEntity.getSku(),
            stockPoolEntity.getAvailable(),
            stockPoolEntity.getVersion()
    );
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
