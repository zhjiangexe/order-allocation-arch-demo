package com.flowzati.archone.inventory.position.onhand.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.position.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.position.infrastructure.persistence.jpa.entity.StockQuantEntity;
import com.flowzati.archone.inventory.position.infrastructure.persistence.jpa.mapper.StockQuantMapper;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockQuant persistence mapper")
class StockQuantMapperTest {

    private static final LocalDate IN_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate EXPIRY_DATE = LocalDate.of(2026, 12, 31);

    @Test
    @DisplayName("應將五個身分維度、兩個數量與 version 一併映射至 entity")
    void mapsDomainToEntity() {
        UUID id = UUID.randomUUID();
        StockQuant stockQuant = new StockQuant(
                id, StockFixtures.OWNER_ID, StockFixtures.LOCATION_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 10, 4, 7L);

        StockQuantEntity entity = StockQuantMapper.toEntity(stockQuant);

        // 五個維度全部都要過去。漏掉任何一個，寫回資料庫時會落到別的一列或違反唯一鍵，
        // 而兩者都不是在這一層就看得出來的錯。
        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOwnerId()).isEqualTo(StockFixtures.OWNER_ID);
        assertThat(entity.getLocationId()).isEqualTo(StockFixtures.LOCATION_ID);
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
        StockQuantEntity entity = new StockQuantEntity(
                id, StockFixtures.OWNER_ID, StockFixtures.LOCATION_ID, "SKU-1", IN_DATE, EXPIRY_DATE, 10, 4, 7L);

        StockQuant stockQuant = StockQuantMapper.toDomain(entity);

        assertThat(stockQuant.getId()).isEqualTo(id);
        assertThat(stockQuant.getOwnerId()).isEqualTo(StockFixtures.OWNER_ID);
        assertThat(stockQuant.getLocationId()).isEqualTo(StockFixtures.LOCATION_ID);
        assertThat(stockQuant.getSkuCode()).isEqualTo("SKU-1");
        assertThat(stockQuant.getInDate()).isEqualTo(IN_DATE);
        assertThat(stockQuant.getExpiryDate()).isEqualTo(EXPIRY_DATE);
        assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
        assertThat(stockQuant.getReservedQuantity()).isEqualTo(4);
        assertThat(stockQuant.availableToPromise()).isEqualTo(6);
        assertThat(stockQuant.getVersion()).isEqualTo(7L);
    }
}
