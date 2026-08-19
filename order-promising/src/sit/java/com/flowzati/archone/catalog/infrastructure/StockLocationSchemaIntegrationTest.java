package com.flowzati.archone.catalog.infrastructure;

import com.flowzati.archone.catalog.domain.aggregate.Facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
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
 * 位置 schema 的形狀斷言。
 *
 * <p>與 {@code OrderingSchemaIntegrationTest} 同一個手法：不經 JPA entity，直接查
 * {@code information_schema} 與 {@code pg_indexes}，形狀錯了就失敗，不會被 entity 的對應
 * 關係遮掉。
 *
 * <p>位置是**搬運的端點**。虛擬位置（supplier／customer／inventory）在這個 change 沒有任何
 * 讀者——它們存在，是因為 {@code usage} 的值域必須一次定完，否則下一個 change 要同時改 CHECK
 * 約束與回頭補種子資料。
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@DisplayName("Stock location schema")
class StockLocationSchemaIntegrationTest {

  private static final UUID WAREHOUSE_ID = uuid(1);
  private static final UUID OTHER_WAREHOUSE_ID = uuid(2);

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Nested
  @DisplayName("表與欄位")
  class TableAndColumns {

    @Test
    @DisplayName("應建立 stock_locations，且持有倉別、代碼與用途")
    void createsStockLocations() {
      assertThat(tableNames()).contains("stock_locations");

      assertThat(columnNames("stock_locations"))
          .contains("id", "facility_id", "code", "name", "usage");
    }

    @Test
    @DisplayName("不應有 parent_id 或 parent_path——多庫位尚未形成位置樹")
    void doesNotCreateTreeColumns() {
      assertThat(columnNames("stock_locations"))
          .doesNotContain("parent_id", "parent_path", "location_id");
    }

    @Test
    @DisplayName("不應有 active——不做多步作業，位置不需要被關掉")
    void doesNotCreateActiveFlag() {
      // Odoo 建倉時把 Input／QC／Output／Packing 全建出來、靠 active 切換步數。本系統目前
      // 沒有停用庫位的行為，加一個恆為 true 的欄位只會增加分支。
      assertThat(columnNames("stock_locations")).doesNotContain("active");
    }
  }

  @Nested
  @DisplayName("用途的值域")
  class Usage {

    @Test
    @DisplayName("四個用途都寫得進去")
    void acceptsTheFourUsages() {
      insertWarehouse(WAREHOUSE_ID, "WH-A");

      insertLocation(uuid(10), WAREHOUSE_ID, "WH-A/Stock", "INTERNAL");
      insertLocation(uuid(11), null, "Vendors", "SUPPLIER");
      insertLocation(uuid(12), null, "Customers", "CUSTOMER");
      insertLocation(uuid(13), null, "Inventory adjustment", "INVENTORY");

      assertThat(usageCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("值域外的用途由資料庫擋下")
    void rejectsUnknownUsage() {
      // 值域是程式邏輯的分支，不是設定：只有 internal 算公司庫存。用 CHECK 而非分類表，
      // 是為了讓「新增第五種用途」不會看起來像資料維護。
      assertThatThrownBy(() -> insertLocation(uuid(20), null, "Production", "PRODUCTION"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Nested
  @DisplayName("倉別與用途的搭配")
  class WarehouseByUsage {

    @Test
    @DisplayName("internal 位置沒有倉別時被拒絕")
    void rejectsInternalWithoutWarehouse() {
      assertThatThrownBy(() -> insertLocation(uuid(30), null, "Orphan stock", "INTERNAL"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("虛擬位置帶了倉別時被拒絕")
    void rejectsVirtualWithWarehouse() {
      // 兩個方向都要擋。只擋一邊時，另一邊的髒資料會安靜地存在。
      insertWarehouse(WAREHOUSE_ID, "WH-A");

      assertThatThrownBy(() -> insertLocation(uuid(31), WAREHOUSE_ID, "Owned vendors", "SUPPLIER"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("一個 Facility 可以有多個 internal 位置")
    void allowsMultipleInternalLocationsForOneFacility() {
      insertWarehouse(WAREHOUSE_ID, "WH-A");
      insertLocation(uuid(40), WAREHOUSE_ID, "WH-A/Stock", "INTERNAL");
      insertLocation(uuid(41), WAREHOUSE_ID, "WH-A/Stock 2", "INTERNAL");

      assertThat(usageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("不同倉各有一個 internal 位置")
    void allowsOneInternalLocationPerWarehouse() {
      insertWarehouse(WAREHOUSE_ID, "WH-A");
      insertWarehouse(OTHER_WAREHOUSE_ID, "WH-B");

      insertLocation(uuid(50), WAREHOUSE_ID, "WH-A/Stock", "INTERNAL");
      insertLocation(uuid(51), OTHER_WAREHOUSE_ID, "WH-B/Stock", "INTERNAL");

      assertThat(usageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("多個虛擬位置仍可共同存在")
    void allowsSeveralVirtualLocations() {
      insertLocation(uuid(60), null, "Vendors", "SUPPLIER");
      insertLocation(uuid(61), null, "Customers", "CUSTOMER");
      insertLocation(uuid(62), null, "Inventory adjustment", "INVENTORY");

      assertThat(usageCount()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("完整性")
  class Integrity {

    @Test
    @DisplayName("代碼在全域唯一")
    void rejectsDuplicateCode() {
      insertWarehouse(WAREHOUSE_ID, "WH-A");
      insertWarehouse(OTHER_WAREHOUSE_ID, "WH-B");
      insertLocation(uuid(70), WAREHOUSE_ID, "Stock", "INTERNAL");

      assertThatThrownBy(() -> insertLocation(uuid(71), OTHER_WAREHOUSE_ID, "Stock", "INTERNAL"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("指向不存在的倉時被拒絕")
    void rejectsUnknownWarehouse() {
      assertThatThrownBy(() -> insertLocation(uuid(80), uuid(999), "Ghost/Stock", "INTERNAL"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  private void insertWarehouse(UUID id, String code) {
    jdbcTemplate.update(
        "INSERT INTO facilities (id, code, name) VALUES (?, ?, ?)", id, code, code);
  }

  private void insertLocation(UUID id, UUID facilityId, String code, String usage) {
    jdbcTemplate.update(
        "INSERT INTO stock_locations (id, facility_id, code, name, usage) VALUES (?, ?, ?, ?, ?)",
        id, facilityId, code, code, usage);
  }

  private int usageCount() {
    Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_locations", Integer.class);
    return count == null ? 0 : count;
  }

  private List<String> tableNames() {
    return jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
        String.class);
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
