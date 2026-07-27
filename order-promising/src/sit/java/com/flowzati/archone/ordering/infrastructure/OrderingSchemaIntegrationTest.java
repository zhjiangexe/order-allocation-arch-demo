package com.flowzati.archone.ordering.infrastructure;

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
  private static final Instant PLACED_AT = Instant.parse("2026-07-27T08:00:00Z");

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
              "requested_node_id")
          .doesNotContain("sku", "quantity");
    }

    @Test
    @DisplayName("orders 不應有 fulfilled_at——它是 R7 才產生的輸出，此階段沒有寫入路徑")
    void doesNotCreateFulfilledAt() {
      assertThat(columnNames("orders")).doesNotContain("fulfilled_at");
    }

    @Test
    @DisplayName("order_lines 不應有 allocated_at——ship-complete 下它恆等於 header 且無 index 需要它")
    void doesNotCreateLineLevelAllocatedAt() {
      assertThat(columnNames("order_lines"))
          .contains("line_no", "owner_id", "sku_code", "quantity", "assigned_node_id", "status",
              "backordered_since")
          .doesNotContain("allocated_at");
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
    @DisplayName("assigned_node_id 應無外鍵——fulfillment_nodes 要等 R2 才存在")
    void leavesAssignedNodeWithoutForeignKey() {
      assertThat(foreignKeyColumns("order_lines")).doesNotContain("assigned_node_id");
      assertThat(foreignKeyColumns("orders")).doesNotContain("requested_node_id");
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
    @DisplayName("FIFO index 應建在 order_lines 上，欄位順序固定且不含 status")
    void createsBackorderFifoIndexOnOrderLines() {
      String indexDefinition = indexDefinition("order_lines", "idx_order_lines_backorder_fifo");

      assertThat(indexDefinition)
          .contains("(owner_id, sku_code, backordered_since, id)")
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
          .contains("(placed_at DESC, id DESC)");
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
        INSERT INTO owners (id, code, name, status, allow_split_shipment)
        VALUES (?, ?, ?, 'ACTIVE', true)
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

  private void seedOrder(UUID id, UUID ownerId, String externalOrderNo) {
    jdbcTemplate.update("""
        INSERT INTO orders (
            id, owner_id, external_order_no, ship_to_zone, ship_to_address,
            promised_delivery_date, status, placed_at)
        VALUES (?, ?, ?, '100', '台北市中正區重慶南路一段 122 號', ?, 'PENDING', ?)
        """, id, ownerId, externalOrderNo,
        Date.valueOf(LocalDate.of(2026, 8, 1)), Timestamp.from(PLACED_AT));
  }

  private void seedLine(
      UUID id, UUID orderId, int lineNo, UUID ownerId, String skuCode, int quantity) {
    jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity, status)
        VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
        """, id, orderId, lineNo, ownerId, skuCode, quantity);
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
