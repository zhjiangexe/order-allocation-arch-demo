package com.flowzati.archone.allocation.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockPool persistence mapper")
class StockPoolMapperTest {

  @Test
  @DisplayName("應將完整 ATP 基礎量與 version 映射至 entity")
  void mapsDomainToEntity() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 4, 7L);

    StockPoolEntity entity = StockPoolMapper.toEntity(stockPool);

    assertThat(entity.getId()).isEqualTo(1L);
    assertThat(entity.getSku()).isEqualTo("SKU-1");
    assertThat(entity.getOnHandQuantity()).isEqualTo(10);
    assertThat(entity.getReservedQuantity()).isEqualTo(4);
    assertThat(entity.getVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("應將 entity 還原為具有相同 ATP 與 version 的 domain model")
  void mapsEntityToDomain() {
    StockPoolEntity entity = new StockPoolEntity(1L, "SKU-1", 10, 4, 7L);

    StockPool stockPool = StockPoolMapper.toDomain(entity);

    assertThat(stockPool.getId()).isEqualTo(1L);
    assertThat(stockPool.getSku()).isEqualTo("SKU-1");
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(4);
    assertThat(stockPool.availableToPromise()).isEqualTo(6);
    assertThat(stockPool.getVersion()).isEqualTo(7L);
  }
}
