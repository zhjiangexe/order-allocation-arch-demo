package com.flowzati.archone.ordering.infrastructure;

import com.flowzati.archone.testsupport.OrderFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 訂單層 schema 的形狀斷言。
 *
 * <p>刻意不經 JPA entity：這些斷言驗的是 migration 本身，而 entity 的改造在後續任務才
 * 進行。以 {@code information_schema} 與 {@code pg_indexes} 直接查，形狀錯了就失敗，
 * 不會被 entity 的對應關係遮掉。
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@DisplayName("Ordering schema")
class OrderingSchemaIntegrationTest {

  private static final UUID OWNER_ID = uuid(1);
  private static final UUID OTHER_OWNER_ID = uuid(2);
  private static final Instant RECEIVED_AT = Instant.parse("2026-07-27T08:00:00Z");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Nested
  @DisplayName("建表順序與欄位")
  class TablesAndColumns {

    @Test
    @DisplayName("應建立四張新表，且 orders 不再持有 sku 與 quantity")
    void createsFourTablesAndStripsOrdersOfLineFields() {
      assertThat(tableNames())
          .contains("owners", "products", "skus", "order_lines", "orders");

      assertThat(columnNames("orders"))
          .contains(
              "owner_id",
              "external_order_no",
              "ship_to_zone",
              "ship_to_address",
              "promised_delivery_date",
              "facility_id")
          .doesNotContain("sku", "quantity", "requested_facility_id");
    }

    @Test
    @DisplayName("orders 應以 fulfilled_at 記錄整單出庫完成時間")
    void createsFulfilledAt() {
      assertThat(columnNames("orders")).contains("fulfilled_at");
    }

    @Test
    @DisplayName("order_lines 不應有狀態也不應有時間戳——三者都恆等於 header 且無人讀")
    void doesNotCreateLineLevelStatusOrTimestamps() {
      assertThat(columnNames("order_lines"))
          .contains("line_no", "owner_id", "sku_code", "quantity")
          // assigned_facility_id 已砍：一張單只從一個倉出、明細不可跨倉，它永遠等於 header。
          //
          // backordered_since 也砍了：它的存在理由是「單表 FIFO index」，而那個查詢從來就是
          // join、排序取自 header——欄位從未被讀到。佇列改以 order_id 排序後連理由的形狀
          // 都不在了。
          //
          // status 是最後被拿掉的一個。它的理由是「REST 逐行揭露，放寬多行之後不必改契約
          // 就能逐行顯示」——但 ship-complete 保證所有行同進同出，多行之後值仍然恆等於
          // header。契約照舊逐行揭露，改由 header 導出。
          .doesNotContain("allocated_at", "assigned_facility_id", "backordered_since", "status");
    }
  }

  @Nested
  @DisplayName("主檔用代理鍵，撞號保護交給 unique constraint")
  class OwnerScopedKeys {

    @Test
    @DisplayName("主檔應與其餘各表一致，使用單欄代理主鍵")
    void usesSurrogatePrimaryKeys() {
      assertThat(primaryKeyColumns("products")).containsExactly("id");
      assertThat(primaryKeyColumns("skus")).containsExactly("id");
      assertThat(primaryKeyColumns("owners")).containsExactly("id");
    }

    // 以下兩支刻意分開：第一個 constraint 違反會讓交易進入 aborted 狀態，同一個交易裡
    // 的後續語句一律失敗於「current transaction is aborted」，斷言就驗不到真正的原因。

    @Test
    @DisplayName("同一貨主的 sku_code 應唯一——放棄複合主鍵不等於放棄這個約束")
    void keepsSkuCodeUniqueWithinOneOwner() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");
      seedSku(OWNER_ID, "SKU-A", "P-1", 520);

      assertThatThrownBy(() -> seedSku(OWNER_ID, "SKU-A", "P-1", 999))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("uq_skus_owner_code");
    }

    @Test
    @DisplayName("同一貨主的 product_code 應唯一")
    void keepsProductCodeUniqueWithinOneOwner() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");

      assertThatThrownBy(() -> seedProduct(OWNER_ID, "P-1", "FROZEN"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("uq_products_owner_code");
    }

    @Test
    @DisplayName("規格應無法指向他貨主的款——外鍵走自然鍵，跨貨主的參照建不起來")
    void rejectsSkuPointingAtAnotherOwnersProduct() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOwner(OTHER_OWNER_ID, "OWNER-B");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");

      assertThatThrownBy(() -> seedSku(OTHER_OWNER_ID, "SKU-A", "P-1", 520))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("fk_skus_product");
    }

    @Test
    @DisplayName("兩個貨主應可各自定義同一個 sku_code")
    void allowsTheSameSkuCodeUnderTwoOwners() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOwner(OTHER_OWNER_ID, "OWNER-B");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");
      seedProduct(OTHER_OWNER_ID, "P-1", "FROZEN");

      seedSku(OWNER_ID, "SKU-A", "P-1", 520);
      seedSku(OTHER_OWNER_ID, "SKU-A", "P-1", 1000);

      assertThat(jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM skus WHERE sku_code = 'SKU-A'", Integer.class))
          .isEqualTo(2);
    }

    @Test
    @DisplayName("order_lines 應以 (owner_id, sku_code) 參照 skus，單獨的 sku_code 無法建立參照")
    void rejectsLineWhoseOwnerDoesNotOwnTheSkuCode() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOwner(OTHER_OWNER_ID, "OWNER-B");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");
      seedSku(OWNER_ID, "SKU-A", "P-1", 520);
      seedOrder(uuid(10), OTHER_OWNER_ID, "EXT-1");

      // sku_code 存在，但不屬於這筆 line 的貨主
      assertThatThrownBy(() -> seedLine(uuid(20), uuid(10), 1, OTHER_OWNER_ID, "SKU-A", 1))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("fk_order_lines_sku");
    }
  }

  @Nested
  @DisplayName("約束")
  class Constraints {

    @Test
    @DisplayName("同一貨主的 external_order_no 應唯一")
    void rejectsDuplicateExternalOrderNoWithinOneOwner() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOrder(uuid(10), OWNER_ID, "EXT-1");

      assertThatThrownBy(() -> seedOrder(uuid(11), OWNER_ID, "EXT-1"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("uq_orders_owner_external_no");
    }

    @Test
    @DisplayName("不同貨主應可使用相同的 external_order_no")
    void allowsTheSameExternalOrderNoAcrossOwners() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOwner(OTHER_OWNER_ID, "OWNER-B");
      seedOrder(uuid(10), OWNER_ID, "EXT-1");
      seedOrder(uuid(11), OTHER_OWNER_ID, "EXT-1");

      assertThat(jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM orders WHERE external_order_no = 'EXT-1'", Integer.class))
          .isEqualTo(2);
    }

    @Test
    @DisplayName("資料庫應拒絕非正數的 line quantity")
    void rejectsNonPositiveLineQuantity() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");
      seedSku(OWNER_ID, "SKU-A", "P-1", 520);
      seedOrder(uuid(10), OWNER_ID, "EXT-1");

      assertThatThrownBy(() -> seedLine(uuid(20), uuid(10), 1, OWNER_ID, "SKU-A", 0))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("ck_order_lines_quantity_positive");
    }

    @Test
    @DisplayName("同一張單的 line_no 應唯一")
    void rejectsDuplicateLineNoWithinOneOrder() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");
      seedSku(OWNER_ID, "SKU-A", "P-1", 520);
      seedSku(OWNER_ID, "SKU-B", "P-1", 1000);
      seedOrder(uuid(10), OWNER_ID, "EXT-1");
      seedLine(uuid(20), uuid(10), 1, OWNER_ID, "SKU-A", 1);

      assertThatThrownBy(() -> seedLine(uuid(21), uuid(10), 1, OWNER_ID, "SKU-B", 1))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("uq_order_lines_order_line_no");
    }

    @Test
    @DisplayName("倉別應以複合外鍵指向 owner_facilities——只擋倉不存在是不夠的")
    void constrainsFacilityByOwnerAssignment() {
      // 單欄 FK 只保證「倉存在」；複合 FK 才保證「這個貨主掛了這個倉」。
      assertThat(foreignKeyColumns("orders")).contains("owner_id", "facility_id");
      assertThat(jdbcTemplate.queryForObject("""
          SELECT count(*) FROM information_schema.table_constraints
          WHERE table_name = 'orders' AND constraint_name = 'fk_orders_owner_facility'
          """, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("指定該貨主沒掛的倉應被資料庫擋下，而不是只擋倉不存在")
    void rejectsWarehouseTheOwnerIsNotAssignedTo() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedOwner(OTHER_OWNER_ID, "OWNER-B");
      UUID facilityId = UUID.randomUUID();
      jdbcTemplate.update(
          "INSERT INTO facilities (id, code, name) VALUES (?, 'WH-X', '倉 X')", facilityId);
      // 只指派給乙貨主
      jdbcTemplate.update(
          "INSERT INTO owner_facilities (owner_id, facility_id) VALUES (?, ?)", OTHER_OWNER_ID, facilityId);

      assertThatThrownBy(() -> insertOrder(UUID.randomUUID(), OWNER_ID, "EXT-1", facilityId))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("fk_orders_owner_facility");
    }

    @Test
    @DisplayName("weight_gram 必須為正")
    void rejectsNonPositiveWeight() {
      seedOwner(OWNER_ID, "OWNER-A");
      seedProduct(OWNER_ID, "P-1", "AMBIENT");

      assertThatThrownBy(() -> seedSku(OWNER_ID, "SKU-A", "P-1", 0))
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("ck_skus_weight_positive");
    }
  }

  @Nested
  @DisplayName("Index")
  class Indexes {

    @Test
    @DisplayName("待配佇列的 index 應建在 order_lines 上，以 order_id 收尾且不含 status")
    void createsDemandFifoIndexOnOrderLines() {
      String indexDefinition = indexDefinition("order_lines", "idx_order_lines_demand_fifo");

      // 排序鍵是 order_id：UUID v7 把時間戳編在主鍵裡，所以它的大小順序就是訂單進入系統的
      // 順序，也就是 FIFO 要的順序。不需要時間欄位，也不需要 tie-breaker。
      assertThat(indexDefinition)
          .contains("(owner_id, sku_code, order_id)")
          .doesNotContain("status");
    }

    @Test
    @DisplayName("orders 上的舊 FIFO index 應隨 sku 離開而消失")
    void dropsTheOrdersBackorderFifoIndex() {
      assertThat(indexNames("orders")).doesNotContain("idx_orders_backorder_fifo");
    }

    @Test
    @DisplayName("最近訂單 index 應保留，方向與 ORDER BY 一致")
    void keepsTheRecentOrdersIndex() {
      assertThat(indexDefinition("orders", "idx_orders_recent"))
          .contains("(received_at DESC, id DESC)");
    }

    @Test
    @DisplayName("skus 應有支援「列出某款的規格」的 index")
    void createsSkusByProductIndex() {
      assertThat(indexDefinition("skus", "idx_skus_product"))
          .contains("(owner_id, product_code)");
    }
  }

  private List<String> tableNames() {
    return jdbcTemplate.queryForList("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        """, String.class);
  }

  private List<String> columnNames(String table) {
    return jdbcTemplate.queryForList("""
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = ?
        """, String.class, table);
  }

  private List<String> primaryKeyColumns(String table) {
    return jdbcTemplate.queryForList("""
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        WHERE tc.table_schema = 'public'
          AND tc.table_name = ?
          AND tc.constraint_type = 'PRIMARY KEY'
        ORDER BY kcu.ordinal_position
        """, String.class, table);
  }

  private List<String> foreignKeyColumns(String table) {
    return jdbcTemplate.queryForList("""
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        WHERE tc.table_schema = 'public'
          AND tc.table_name = ?
          AND tc.constraint_type = 'FOREIGN KEY'
        """, String.class, table);
  }

  private List<String> indexNames(String table) {
    return jdbcTemplate.queryForList("""
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = ?
        """, String.class, table);
  }

  private String indexDefinition(String table, String indexName) {
    return jdbcTemplate.queryForObject("""
        SELECT indexdef
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = ?
          AND indexname = ?
        """, String.class, table, indexName);
  }

  private void seedOwner(UUID id, String code) {
    jdbcTemplate.update("""
        INSERT INTO owners (id, code, name)
        VALUES (?, ?, ?)
        """, id, code, code);
  }

  private void seedProduct(UUID ownerId, String productCode, String temperatureZone) {
    jdbcTemplate.update("""
        INSERT INTO products (id, owner_id, product_code, name, temperature_zone)
        VALUES (?, ?, ?, ?, ?)
        """, UUID.randomUUID(), ownerId, productCode, productCode, temperatureZone);
  }

  private void seedSku(UUID ownerId, String skuCode, String productCode, int weightGram) {
    jdbcTemplate.update("""
        INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), ownerId, skuCode, productCode, skuCode, weightGram);
  }

  /** 建一張單，順帶把它需要的倉庫與指派備齊——倉別必填且有複合外鍵，缺了寫不進去。 */
  private void seedOrder(UUID id, UUID ownerId, String externalOrderNo) {
    UUID facilityId = defaultNodeFor(ownerId);
    insertOrder(id, ownerId, externalOrderNo, facilityId);
  }

  private UUID defaultNodeFor(UUID ownerId) {
    UUID facilityId = UUID.nameUUIDFromBytes(("facility-" + ownerId).getBytes());
    jdbcTemplate.update("""
        INSERT INTO facilities (id, code, name)
        VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING
        """, facilityId, "WH-" + facilityId, "測試倉");
    jdbcTemplate.update("""
        INSERT INTO owner_facilities (owner_id, facility_id)
        VALUES (?, ?) ON CONFLICT DO NOTHING
        """, ownerId, facilityId);
    return facilityId;
  }

  private void insertOrder(UUID id, UUID ownerId, String externalOrderNo, UUID facilityId) {
    jdbcTemplate.update("""
        INSERT INTO orders (
            id, owner_id, external_order_no, facility_id, ship_to_zone, ship_to_address,
            promised_delivery_date, dispatch_by, release_priority, status, received_at)
        VALUES (?, ?, ?, ?, '100', '台北市中正區重慶南路一段 122 號', ?, ?, 50,
                'PENDING', ?)
        """, id, ownerId, externalOrderNo, facilityId,
        Date.valueOf(LocalDate.of(2026, 8, 1)), Timestamp.from(OrderFixtures.DISPATCH_BY),
        Timestamp.from(RECEIVED_AT));
  }

  private void seedLine(
      UUID id, UUID orderId, int lineNo, UUID ownerId, String skuCode, int quantity) {
    jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity)
        VALUES (?, ?, ?, ?, ?, ?)
        """, id, orderId, lineNo, ownerId, skuCode, quantity);
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
