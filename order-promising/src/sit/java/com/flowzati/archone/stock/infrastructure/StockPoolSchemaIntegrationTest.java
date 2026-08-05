package com.flowzati.archone.stock.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 庫存 schema 的形狀斷言：**庫存掛在位置上，不掛在倉上**。
 *
 * <p>不經 JPA entity，直接查 {@code information_schema} 與 {@code pg_indexes}——形狀錯了就
 * 失敗，不會被 entity 的對應關係遮掉。
 *
 * <p>庫存以位置為端點；一個 Facility 可有多個 internal locations，查詢與命令必須明確帶位置。
 * 它要換到的是**搬運有端點可指**，而倉當不了那個端點。
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@DisplayName("Stock pool schema")
class StockPoolSchemaIntegrationTest {

  private static final UUID OWNER_ID = uuid(1);
  private static final UUID WAREHOUSE_ID = uuid(2);
  private static final UUID INTERNAL_LOCATION_ID = uuid(3);
  private static final UUID CUSTOMER_LOCATION_ID = uuid(4);
  private static final String SKU = "SKU-A";
  private static final LocalDate IN_DATE = LocalDate.of(2026, 1, 1);
  private static final LocalDate EXPIRY_DATE = LocalDate.of(2027, 1, 1);

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Nested
  @DisplayName("身分的維度")
  class Identity {

    @Test
    @DisplayName("庫存應持有位置而不是倉")
    void holdsALocationRatherThanAWarehouse() {
      assertThat(columnNames("stock_pools"))
          .contains("owner_id", "location_id", "sku_code", "in_date", "expiry_date")
          .doesNotContain("facility_id");
    }

    @Test
    @DisplayName("五維全等才是同一批——位置不同即為不同的貨")
    void treatsTwoLocationsAsDifferentStock() {
      seedCatalog();
      insertLocation(uuid(30), WAREHOUSE_ID, "WH/Stock-2", "INTERNAL", uuid(20), "WH-2");

      insertPool(uuid(40), INTERNAL_LOCATION_ID, 10);
      insertPool(uuid(41), uuid(30), 10);

      assertThat(poolCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一位置的五維重複由 unique 擋下")
    void rejectsDuplicateIdentity() {
      seedCatalog();
      insertPool(uuid(42), INTERNAL_LOCATION_ID, 10);

      assertThatThrownBy(() -> insertPool(uuid(43), INTERNAL_LOCATION_ID, 5))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("uq_stock_pools_batch");
    }
  }

  @Nested
  @DisplayName("只能掛在內部位置")
  class InternalOnly {

    @Test
    @DisplayName("指向虛擬位置的庫存被拒絕")
    void rejectsStockInAVirtualLocation() {
      // 外鍵擋不到這件事——它只保證位置存在。少了這條約束，「系統宣稱在一個它不經營的
      // 地方持有貨」寫得進去，而症狀要到總量對帳時才出現。
      seedCatalog();

      assertThatThrownBy(() -> insertPool(uuid(50), CUSTOMER_LOCATION_ID, 10))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("指向不存在的位置被拒絕")
    void rejectsUnknownLocation() {
      seedCatalog();

      assertThatThrownBy(() -> insertPool(uuid(51), uuid(999), 10))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Nested
  @DisplayName("FEFO 取批")
  class Fefo {

    @Test
    @DisplayName("index 的第二欄應是位置，其餘結構不動")
    void keepsTheFefoIndexShapeWithLocationInPlaceOfWarehouse() {
      // 等值篩選在前、排序鍵其次、id 作為最後的 tie-breaker——這個結構的理由與位置無關，
      // 因此只有第二欄換名字。三層排序鍵不是裝飾：同效期不同日到貨很常見，少了 in_date
      // 與 id，配貨結果不可重現，防死鎖的寫入排序也失去依據。
      assertThat(indexDefinitionOf("idx_stock_pools_fefo"))
          .contains("owner_id", "location_id", "sku_code", "expiry_date", "in_date", "id")
          .doesNotContain("facility_id");
    }
  }

  private void seedCatalog() {
    jdbcTemplate.update(
        "INSERT INTO owners (id, code, name) VALUES (?, 'OWNER-A', 'A')", OWNER_ID);
    jdbcTemplate.update(
        "INSERT INTO products (id, owner_id, product_code, name, temperature_zone) "
            + "VALUES (?, ?, 'P-A', 'P', 'AMBIENT')", uuid(10), OWNER_ID);
    jdbcTemplate.update(
        "INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram) "
            + "VALUES (?, ?, ?, 'P-A', 'spec', 100)", uuid(11), OWNER_ID, SKU);
    jdbcTemplate.update(
        "INSERT INTO facilities (id, code, name) VALUES (?, 'WH-A', 'A')", WAREHOUSE_ID);

    insertLocation(INTERNAL_LOCATION_ID, WAREHOUSE_ID, "WH-A/Stock", "INTERNAL", null, null);
    insertLocation(CUSTOMER_LOCATION_ID, null, "Customers", "CUSTOMER", null, null);
  }

  private void insertLocation(
      UUID id, UUID facilityId, String code, String usage, UUID extraFacilityId, String extraNodeCode) {
    if (extraFacilityId != null) {
      jdbcTemplate.update(
          "INSERT INTO facilities (id, code, name) VALUES (?, ?, ?)",
          extraFacilityId, extraNodeCode, extraNodeCode);
      facilityId = extraFacilityId;
    }
    jdbcTemplate.update(
        "INSERT INTO stock_locations (id, facility_id, code, name, usage) VALUES (?, ?, ?, ?, ?)",
        id, facilityId, code, code, usage);
  }

  private void insertPool(UUID id, UUID locationId, int onHand) {
    jdbcTemplate.update(
        "INSERT INTO stock_pools "
            + "(id, owner_id, location_id, sku_code, in_date, expiry_date, on_hand_quantity, "
            + " reserved_quantity, version) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
        id, OWNER_ID, locationId, SKU, IN_DATE, EXPIRY_DATE, onHand);
  }

  private int poolCount() {
    Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_pools", Integer.class);
    return count == null ? 0 : count;
  }

  private String indexDefinitionOf(String indexName) {
    return jdbcTemplate.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
        String.class,
        indexName);
  }

  private List<String> columnNames(String table) {
    return jdbcTemplate.queryForList(
        "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ?",
        String.class,
        table);
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", seed));
  }
}
