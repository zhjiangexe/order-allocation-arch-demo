package com.flowzati.archone.catalog.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.catalog.domain.aggregate.Owner;
import com.flowzati.archone.catalog.domain.aggregate.Product;
import com.flowzati.archone.catalog.domain.aggregate.Sku;
import com.flowzati.archone.catalog.domain.type.TemperatureZone;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerEntity;
import com.flowzati.archone.catalog.infrastructure.entity.ProductEntity;
import com.flowzati.archone.catalog.infrastructure.entity.SkuEntity;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Catalog persistence mappers")
class CatalogMapperTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Nested
    @DisplayName("Owner")
    class Owners {

        @Test
        @DisplayName("應雙向映射代號、名稱、狀態與拆單許可")
        void mapsBothWays() {
            Owner owner = new Owner(OWNER_ID, "OWNER-A", "甲貨主");

            OwnerEntity entity = OwnerMapper.toEntity(owner);
            Owner restored = OwnerMapper.toDomain(entity);

            assertThat(entity.getId()).isEqualTo(OWNER_ID);
            assertThat(entity.getCode()).isEqualTo("OWNER-A");
            assertThat(entity.getName()).isEqualTo("甲貨主");

            assertThat(restored.getId()).isEqualTo(OWNER_ID);
            assertThat(restored.getCode()).isEqualTo("OWNER-A");
            assertThat(restored.getName()).isEqualTo("甲貨主");
        }
    }

    @Nested
    @DisplayName("Product")
    class Products {

        @Test
        @DisplayName("應雙向映射識別、自然鍵與溫層")
        void mapsBothWays() {
            UUID productId = UUID.randomUUID();
            Product product = new Product(productId, OWNER_ID, "P-1", "冷凍水餃", TemperatureZone.FROZEN);

            ProductEntity entity = ProductMapper.toEntity(product);
            Product restored = ProductMapper.toDomain(entity);

            assertThat(entity.getId()).isEqualTo(productId);
            assertThat(restored.getId()).isEqualTo(productId);
            assertThat(entity.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(entity.getProductCode()).isEqualTo("P-1");
            assertThat(entity.getName()).isEqualTo("冷凍水餃");
            assertThat(entity.getTemperatureZone()).isEqualTo(TemperatureZone.FROZEN);

            assertThat(restored.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(restored.getProductCode()).isEqualTo("P-1");
            assertThat(restored.getName()).isEqualTo("冷凍水餃");
            assertThat(restored.getTemperatureZone()).isEqualTo(TemperatureZone.FROZEN);
        }
    }

    @Nested
    @DisplayName("Sku")
    class Skus {

        @Test
        @DisplayName("應雙向映射識別、自然鍵、所屬款、規格名與重量")
        void mapsBothWays() {
            UUID skuId = UUID.randomUUID();
            Sku sku = new Sku(skuId, OWNER_ID, "SKU-A", "P-1", "500ml", 520);

            SkuEntity entity = SkuMapper.toEntity(sku);
            Sku restored = SkuMapper.toDomain(entity);

            assertThat(entity.getId()).isEqualTo(skuId);
            assertThat(restored.getId()).isEqualTo(skuId);
            assertThat(entity.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(entity.getSkuCode()).isEqualTo("SKU-A");
            assertThat(entity.getProductCode()).isEqualTo("P-1");
            assertThat(entity.getSpecName()).isEqualTo("500ml");
            assertThat(entity.getWeightGram()).isEqualTo(520);

            assertThat(restored.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(restored.getSkuCode()).isEqualTo("SKU-A");
            assertThat(restored.getProductCode()).isEqualTo("P-1");
            assertThat(restored.getSpecName()).isEqualTo("500ml");
            assertThat(restored.getWeightGram()).isEqualTo(520);
        }

        @Test
        @DisplayName("自然鍵的兩半在往返後都不得遺失——只剩 skuCode 就無法指回正確的貨主")
        void keepsBothHalvesOfTheNaturalKey() {
            UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            Sku first = new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", "P-1", "500ml", 520);
            Sku second = new Sku(UUID.randomUUID(), otherOwnerId, "SKU-A", "P-1", "1kg", 1000);

            Sku restoredFirst = SkuMapper.toDomain(SkuMapper.toEntity(first));
            Sku restoredSecond = SkuMapper.toDomain(SkuMapper.toEntity(second));

            assertThat(restoredFirst.getSkuCode()).isEqualTo(restoredSecond.getSkuCode());
            assertThat(restoredFirst.getOwnerId()).isNotEqualTo(restoredSecond.getOwnerId());
            assertThat(restoredFirst.getWeightGram()).isNotEqualTo(restoredSecond.getWeightGram());
        }
    }
}
