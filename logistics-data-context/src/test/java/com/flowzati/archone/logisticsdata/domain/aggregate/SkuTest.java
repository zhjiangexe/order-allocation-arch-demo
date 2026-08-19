package com.flowzati.archone.logisticsdata.domain.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.logisticsdata.domain.type.TemperatureZone;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Sku")
class SkuTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("應以代理鍵識別，持有貨主與規格編碼構成的自然鍵及規格名與重量")
    void isIdentifiedByOwnerAndCodeAndCarriesSpecAndWeight() {
        Sku sku = new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", "P-1", "500ml", 520);

        assertThat(sku.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(sku.getSkuCode()).isEqualTo("SKU-A");
        assertThat(sku.getProductCode()).isEqualTo("P-1");
        assertThat(sku.getSpecName()).isEqualTo("500ml");
        assertThat(sku.getWeightGram()).isEqualTo(520);
    }

    @Test
    @DisplayName("不應持有溫層——溫層屬款層級，放這裡會讓同款兩溫層變成可表達的狀態")
    void doesNotCarryTemperatureZone() {
        assertThat(Sku.class.getDeclaredFields()).extracting(Field::getName).doesNotContain("temperatureZone");

        assertThat(Sku.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("getTemperatureZone");
    }

    @Test
    @DisplayName("同一款的兩個規格重量可不同，而溫層一律取自其款")
    void twoSpecificationsOfOneProductShareItsTemperatureZone() {
        Product product = new Product(UUID.randomUUID(), OWNER_ID, "P-1", "冷凍水餃", TemperatureZone.FROZEN);
        Sku small = new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", "P-1", "500g", 500);
        Sku large = new Sku(UUID.randomUUID(), OWNER_ID, "SKU-B", "P-1", "1kg", 1000);

        assertThat(List.of(small, large)).allMatch(sku -> sku.getProductCode().equals(product.getProductCode()));
        assertThat(small.getWeightGram()).isNotEqualTo(large.getWeightGram());
        // 溫層只有一個來源，兩個規格不可能報出不同的值
        assertThat(product.getTemperatureZone()).isEqualTo(TemperatureZone.FROZEN);
    }

    @ParameterizedTest(name = "[{index}] weightGram={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("重量必須為正——它是 R6 成本函數的運費基準")
    void rejectsNonPositiveWeight(int weightGram) {
        assertThatThrownBy(() -> new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", "P-1", "500ml", weightGram))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Weight");
    }

    @Test
    @DisplayName("識別碼為必填")
    void rejectsMissingId() {
        assertThatThrownBy(() -> new Sku(null, OWNER_ID, "SKU-A", "P-1", "500ml", 520))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU ID");
    }

    @Test
    @DisplayName("貨主為必填——規格編碼單獨存在時不指向任何東西")
    void rejectsMissingOwnerId() {
        assertThatThrownBy(() -> new Sku(UUID.randomUUID(), null, "SKU-A", "P-1", "500ml", 520))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner ID");
    }

    @ParameterizedTest(name = "[{index}] skuCode={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("規格編碼為必填")
    void rejectsBlankSkuCode(String skuCode) {
        assertThatThrownBy(() -> new Sku(UUID.randomUUID(), OWNER_ID, skuCode, "P-1", "500ml", 520))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU code");
    }

    @ParameterizedTest(name = "[{index}] productCode={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("所屬款號為必填")
    void rejectsBlankProductCode(String productCode) {
        assertThatThrownBy(() -> new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", productCode, "500ml", 520))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product code");
    }

    @ParameterizedTest(name = "[{index}] specName={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("規格名為必填——畫面顯示「品名 · 規格」的後段")
    void rejectsBlankSpecName(String specName) {
        assertThatThrownBy(() -> new Sku(UUID.randomUUID(), OWNER_ID, "SKU-A", "P-1", specName, 520))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Specification name");
    }
}
