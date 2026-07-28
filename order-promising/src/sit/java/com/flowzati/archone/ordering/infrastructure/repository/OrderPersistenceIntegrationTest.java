package com.flowzati.archone.ordering.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import com.flowzati.archone.ordering.infrastructure.repository.jpa.JpaOrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.ordering.infrastructure.entity.OrderLineEntity;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(
    properties = "spring.data.jpa.repositories.enabled=false",
    showSql = false
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    OrderRepositoryImpl.class,
    OrderPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("Order PostgreSQL persistence adapter")
class OrderPersistenceIntegrationTest {

  private static final Instant PLACED_AT = Instant.parse("2026-07-23T08:00:00Z");
  private static final Instant BACKORDERED_AT = Instant.parse("2026-07-23T08:01:00Z");

  @Autowired
  private JpaOrderRepository jpaRepository;

  @Autowired
  private OrderRepositoryImpl repositoryAdapter;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** 訂單行的 {@code (owner_id, sku_code)} 有外鍵指向 {@code skus}，主檔必須先存在。 */
  @BeforeEach
  void seedCatalog() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1", "SKU-2");
  }

  @Test
  @DisplayName("應寫入並還原完整 Order state 與 version")
  void persistsAndRestoresOrder() {
    UUID orderId = uuid(1);
    Order order = OrderFixtures.backorderedOrder(
        orderId, OrderFixtures.OWNER_ID, "SKU-1", 3, PLACED_AT, BACKORDERED_AT, null);

    repositoryAdapter.save(order);
    jpaRepository.flush();
    entityManager.clear();

    Order restored = repositoryAdapter.findById(orderId).orElseThrow();

    assertThat(restored.getId()).isEqualTo(orderId);
    assertThat(restored.getOwnerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(restored.getDemand()).isEqualTo(Map.of("SKU-1", 3));
    assertThat(restored.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(restored.getPlacedAt()).isEqualTo(PLACED_AT);
    assertThat(restored.getAllocatedAt()).isNull();
    assertThat(restored.getBackOrderedSince()).isEqualTo(BACKORDERED_AT);
    assertThat(restored.getCancelledAt()).isNull();
    assertThat(restored.getVersion()).isZero();
    assertThat(restored.releaseDomainEvents()).isEmpty();
  }

  @Test
  @DisplayName("找不到 Order 時應忠實回傳 empty Optional")
  void returnsEmptyWhenOrderDoesNotExist() {
    assertThat(repositoryAdapter.findById(uuid(1))).isEmpty();
  }

  @Test
  @DisplayName("應只查同 SKU 的 BACKORDERED orders 並以時間及 ID 穩定排序")
  void findsBackordersInStableFifoOrder() {
    Instant later = BACKORDERED_AT.plusSeconds(1);
    persistOrder(uuid(3), "SKU-1", OrderStatus.BACKORDERED, null, later);
    persistOrder(uuid(2), "SKU-1", OrderStatus.BACKORDERED, null, BACKORDERED_AT);
    persistOrder(uuid(1), "SKU-1", OrderStatus.BACKORDERED, null, BACKORDERED_AT);
    persistOrder(uuid(4), "SKU-2", OrderStatus.BACKORDERED, null, BACKORDERED_AT);
    persistOrder(uuid(5), "SKU-1", OrderStatus.PENDING, null, null);
    entityManager.clear();

    List<Order> result = repositoryAdapter.findBackordersBySkuInFifoOrder("SKU-1");

    assertThat(result).extracting(Order::getId)
        .containsExactly(uuid(1), uuid(2), uuid(3));
    assertThat(result).allMatch(order -> order.getStatus() == OrderStatus.BACKORDERED);
  }

  @Test
  @DisplayName("stale Order snapshot 寫回時應被 optimistic locking 拒絕")
  void rejectsStaleVersion() {
    UUID orderId = uuid(1);
    persistOrder(orderId, "SKU-1", OrderStatus.PENDING, null, null);
    entityManager.clear();
    Order staleOrder = repositoryAdapter.findById(orderId).orElseThrow();

    jdbcTemplate.update(
        "UPDATE orders SET version = version + 1 WHERE id = ?",
        orderId
    );
    entityManager.clear();
    staleOrder.markBackOrdered(BACKORDERED_AT);

    assertThatThrownBy(() -> {
      repositoryAdapter.save(staleOrder);
      jpaRepository.flush();
    }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("資料庫應拒絕非正數的訂單行數量——數量隨 sku 一起搬到了行上")
  void rejectsNonPositiveLineQuantity(int quantity) {
    persistOrder(uuid(1), "SKU-1", OrderStatus.PENDING, null, null);

    assertThatThrownBy(() -> jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity, status)
        VALUES (?, ?, 2, ?, ?, ?, 'PENDING')
        """, uuid(9), uuid(1), OrderFixtures.OWNER_ID, "SKU-2", quantity))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_order_lines_quantity_positive");
  }

  @Test
  @DisplayName("訂單行指向該貨主沒有的 SKU 時應被外鍵擋下")
  void rejectsLineReferencingASkuTheOwnerDoesNotHave() {
    Order order = OrderFixtures.pendingOrder(uuid(1), "SKU-NOT-IN-CATALOG", 3, PLACED_AT);

    // 應用層刻意不預先查主檔：多一層檢查只換到更好的錯誤訊息，卻多一條「檢查通過但
    // 寫入時已被刪除」的競爭路徑。完整性由外鍵保證。
    //
    // 只斷言「被擋下」，不斷言「資料庫裡沒殘留」——後者靠的是交易回滾，而那已由
    // DatabaseFoundationIntegrationTest 的「交易失敗時應回滾資料庫變更」驗過。在這裡再驗
    // 一次得跳出交易（constraint 違反後同一交易的任何查詢都會失敗於 aborted），換到的只是
    // 同一個機制的第二份覆蓋。
    assertThatThrownBy(() -> {
      repositoryAdapter.save(order);
      jpaRepository.flush();
    }).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_order_lines_sku");
  }

  @Test
  @DisplayName("同一貨主的同一上游單號不得建立第二筆訂單")
  void rejectsDuplicateExternalOrderNoWithinOneOwner() {
    repositoryAdapter.save(orderWithExternalNo(uuid(1), "EXT-DUP"));
    jpaRepository.flush();

    // 這裡失敗是刻意的：本階段尚未實作冪等，重送得到的是明確的錯誤而不是既有訂單。
    // 倉儲場景下，「靜默建立第二筆」是資料事故，「明確報錯」只是錯誤訊息。
    assertThatThrownBy(() -> {
      repositoryAdapter.save(orderWithExternalNo(uuid(2), "EXT-DUP"));
      jpaRepository.flush();
    }).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_orders_owner_external_no");
  }

  @Test
  @DisplayName("不同貨主應可使用相同的上游單號")
  void allowsTheSameExternalOrderNoAcrossOwners() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OTHER_OWNER_ID, "SKU-1");
    repositoryAdapter.save(orderWithExternalNo(uuid(1), "EXT-SHARED"));
    repositoryAdapter.save(Order.rehydrate(
        uuid(2),
        OrderFixtures.OTHER_OWNER_ID,
        "EXT-SHARED",
        OrderFixtures.deliveryTerms(),
        List.of(OrderLine.create(
            uuid(12), 1, OrderFixtures.OTHER_OWNER_ID, "SKU-1", 3)),
        OrderStatus.PENDING,
        PLACED_AT, null, null, null, null));
    jpaRepository.flush();

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE external_order_no = 'EXT-SHARED'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("應以下單時間遞減取最近訂單，並在時間相同時以 ID 穩定排序")
  void findsRecentOrdersInStableDescendingOrder() {
    // 前三筆刻意共用同一個 placed_at：沒有 id 作為 tie-breaker 的話，重複查詢的順序不保證一致
    persistOrder(uuid(1), "SKU-1", OrderStatus.PENDING, null, null);
    persistOrder(uuid(2), "SKU-1", OrderStatus.PENDING, null, null);
    persistOrder(uuid(3), "SKU-2", OrderStatus.PENDING, null, null);
    persistOrderAt(uuid(4), "SKU-1", PLACED_AT.plusSeconds(1));
    entityManager.clear();

    assertThat(repositoryAdapter.findRecent(10)).extracting(Order::getId)
        .containsExactly(uuid(4), uuid(3), uuid(2), uuid(1));
    assertThat(repositoryAdapter.findRecent(2)).extracting(Order::getId)
        .containsExactly(uuid(4), uuid(3));
    assertThat(repositoryAdapter.findRecent(10)).extracting(Order::getId)
        .containsExactly(uuid(4), uuid(3), uuid(2), uuid(1));
  }

  @Test
  @DisplayName("migration 應建立支援最近訂單查詢的 index，方向與 ORDER BY 一致")
  void createsRecentOrdersIndex() {
    String indexDefinition = jdbcTemplate.queryForObject("""
        SELECT indexdef
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'orders'
          AND indexname = 'idx_orders_recent'
        """, String.class);

    assertThat(indexDefinition).contains("(placed_at DESC, id DESC)");
  }

  /** 一張指定上游單號的訂單——上游單號正是 unique constraint 的一半。 */
  private Order orderWithExternalNo(UUID orderId, String externalOrderNo) {
    return Order.rehydrate(
        orderId,
        OrderFixtures.OWNER_ID,
        externalOrderNo,
        OrderFixtures.deliveryTerms(),
        List.of(OrderLine.create(UUID.randomUUID(), 1, OrderFixtures.OWNER_ID, "SKU-1", 3)),
        OrderStatus.PENDING,
        PLACED_AT, null, null, null, null);
  }

  private void persistOrder(
      UUID id,
      String sku,
      OrderStatus status,
      Instant allocatedAt,
      Instant backorderedSince
  ) {
    persistOrder(id, OrderFixtures.OWNER_ID, sku, status, allocatedAt, backorderedSince, PLACED_AT);
  }

  private void persistOrder(
      UUID id,
      UUID ownerId,
      String sku,
      OrderStatus status,
      Instant allocatedAt,
      Instant backorderedSince,
      Instant placedAt
  ) {
    jpaRepository.saveAndFlush(new OrderEntity(
        id,
        ownerId,
        "EXT-" + id,
        "100",
        "台北市中正區重慶南路一段 122 號",
        LocalDate.of(2026, 8, 1),
        null,
        List.of(new OrderLineEntity(
            UUID.randomUUID(), 1, ownerId, sku, 1, status, backorderedSince, null)),
        status,
        placedAt,
        allocatedAt,
        backorderedSince,
        null,
        null
    ));
  }

  private void persistOrderAt(UUID id, String sku, Instant placedAt) {
    persistOrder(id, OrderFixtures.OWNER_ID, sku, OrderStatus.PENDING, null, null, placedAt);
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = JpaOrderRepository.class)
  static class RepositoryConfiguration {
  }
}
