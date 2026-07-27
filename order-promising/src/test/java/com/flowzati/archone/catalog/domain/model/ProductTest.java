package com.flowzati.archone.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Product")
class ProductTest {

  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  @DisplayName("應以代理鍵識別，持有貨主與款號構成的自然鍵及溫層")
  void isIdentifiedByOwnerAndCodeAndCarriesTemperatureZone() {
    Product product = new Product(UUID.randomUUID(), OWNER_ID, "P-1", "冷凍水餃", TemperatureZone.FROZEN);

    assertThat(product.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(product.getProductCode()).isEqualTo("P-1");
    assertThat(product.getName()).isEqualTo("冷凍水餃");
    assertThat(product.getTemperatureZone()).isEqualTo(TemperatureZone.FROZEN);
  }

  @Test
  @DisplayName("兩個貨主的同一個款號應是相異的兩款")
  void treatsTheSameProductCodeUnderTwoOwnersAsDistinct() {
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Product ambient = new Product(UUID.randomUUID(), OWNER_ID, "P-1", "烏龍茶", TemperatureZone.AMBIENT);
    Product frozen = new Product(UUID.randomUUID(), otherOwnerId, "P-1", "冷凍水餃", TemperatureZone.FROZEN);

    assertThat(ambient.getProductCode()).isEqualTo(frozen.getProductCode());
    assertThat(ambient.getOwnerId()).isNotEqualTo(frozen.getOwnerId());
    assertThat(ambient.getTemperatureZone()).isNotEqualTo(frozen.getTemperatureZone());
  }

  @Test
  @DisplayName("識別碼為必填")
  void rejectsMissingId() {
    assertThatThrownBy(() -> new Product(null, OWNER_ID, "P-1", "烏龍茶", TemperatureZone.AMBIENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Product ID");
  }

  @Test
  @DisplayName("貨主為必填——款號單獨存在時不指向任何東西")
  void rejectsMissingOwnerId() {
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), null, "P-1", "烏龍茶", TemperatureZone.AMBIENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Owner ID");
  }

  @ParameterizedTest(name = "[{index}] productCode={0}")
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("款號為必填")
  void rejectsBlankProductCode(String productCode) {
    assertThatThrownBy(
        () -> new Product(UUID.randomUUID(), OWNER_ID, productCode, "烏龍茶", TemperatureZone.AMBIENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Product code");
  }

  @ParameterizedTest(name = "[{index}] name={0}")
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("品名為必填——畫面顯示「品名 · 規格」的前段")
  void rejectsBlankName(String name) {
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), OWNER_ID, "P-1", name, TemperatureZone.AMBIENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Product name");
  }

  @Test
  @DisplayName("溫層為必填——R6 以它作為節點能力的硬約束")
  void rejectsMissingTemperatureZone() {
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), OWNER_ID, "P-1", "烏龍茶", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temperature zone");
  }
}
