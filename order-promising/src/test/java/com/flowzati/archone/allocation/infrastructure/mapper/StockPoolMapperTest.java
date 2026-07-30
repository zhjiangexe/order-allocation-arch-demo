package com.flowzati.archone.allocation.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockPool persistence mapper")
class StockPoolMapperTest {

  private static final LocalDate IN_DATE = LocalDate.of(2026, 1, 5);
  private static final LocalDate EXPIRY_DATE = LocalDate.of(2026, 12, 31);

  @Test
  @DisplayName("應將五個身分維度、兩個數量與 version 一併映射至 entity")
  void mapsDomainToEntity() {
    UUID id = UUID.randomUUID();
    StockPool stockPool = new StockPool(
        id, StockFixtures.OWNER_ID, StockFixtures.NODE_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 10, 4, 7L);

    StockPoolEntity entity = StockPoolMapper.toEntity(stockPool);

    // 五個維度全部都要過去。漏掉任何一個，寫回資料庫時會落到別的一列或違反唯一鍵，
    // 而兩者都不是在這一層就看得出來的錯。
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getOwnerId()).isEqualTo(StockFixtures.OWNER_ID);
    assertThat(entity.getNodeId()).isEqualTo(StockFixtures.NODE_ID);
    assertThat(entity.getSkuCode()).isEqualTo("SKU-1");
    assertThat(entity.getInDate()).isEqualTo(IN_DATE);
    assertThat(entity.getExpiryDate()).isEqualTo(EXPIRY_DATE);
    assertThat(entity.getOnHandQuantity()).isEqualTo(10);
    assertThat(entity.getReservedQuantity()).isEqualTo(4);
    assertThat(entity.getVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("應將 entity 還原為具有相同維度、ATP 與 version 的 domain model")
  void mapsEntityToDomain() {
    UUID id = UUID.randomUUID();
    StockPoolEntity entity = new StockPoolEntity(
        id, StockFixtures.OWNER_ID, StockFixtures.NODE_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 10, 4, 7L);

    StockPool stockPool = StockPoolMapper.toDomain(entity);

    assertThat(stockPool.getId()).isEqualTo(id);
    assertThat(stockPool.getOwnerId()).isEqualTo(StockFixtures.OWNER_ID);
    assertThat(stockPool.getNodeId()).isEqualTo(StockFixtures.NODE_ID);
    assertThat(stockPool.getSkuCode()).isEqualTo("SKU-1");
    assertThat(stockPool.getInDate()).isEqualTo(IN_DATE);
    assertThat(stockPool.getExpiryDate()).isEqualTo(EXPIRY_DATE);
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(4);
    assertThat(stockPool.availableToPromise()).isEqualTo(6);
    assertThat(stockPool.getVersion()).isEqualTo(7L);
  }
}
