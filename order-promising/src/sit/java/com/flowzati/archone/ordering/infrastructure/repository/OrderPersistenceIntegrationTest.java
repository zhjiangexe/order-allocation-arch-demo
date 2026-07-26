package com.flowzati.archone.ordering.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import com.flowzati.archone.ordering.infrastructure.repository.jpa.JpaOrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

  @Test
  @DisplayName("應寫入並還原完整 Order state 與 version")
  void persistsAndRestoresOrder() {
    UUID orderId = uuid(1);
    Order order = Order.rehydrate(
        orderId,
        "SKU-1",
        3,
        OrderStatus.BACKORDERED,
        PLACED_AT,
        null,
        BACKORDERED_AT,
        null,
        null
    );

    repositoryAdapter.save(order);
    jpaRepository.flush();
    entityManager.clear();

    Order restored = repositoryAdapter.findById(orderId).orElseThrow();

    assertThat(restored.getId()).isEqualTo(orderId);
    assertThat(restored.getSku()).isEqualTo("SKU-1");
    assertThat(restored.getQuantity()).isEqualTo(3);
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
  @DisplayName("資料庫應拒絕非正數 Order quantity")
  void rejectsNonPositiveQuantity(int quantity) {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        INSERT INTO orders (id, sku, quantity, status, placed_at)
        VALUES (?, ?, ?, ?, ?)
        """, uuid(1), "SKU-1", quantity, "PENDING", Timestamp.from(PLACED_AT)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_orders_quantity_positive");
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

  @Test
  @DisplayName("migration 應建立支援穩定 FIFO 的複合 index")
  void createsStableFifoIndex() {
    String indexDefinition = jdbcTemplate.queryForObject("""
        SELECT indexdef
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'orders'
          AND indexname = 'idx_orders_backorder_fifo'
        """, String.class);

    assertThat(indexDefinition)
        .contains("(sku, status, backordered_since, id)");
  }

  private void persistOrder(
      UUID id,
      String sku,
      OrderStatus status,
      Instant allocatedAt,
      Instant backorderedSince
  ) {
    jpaRepository.saveAndFlush(new OrderEntity(
        id,
        sku,
        1,
        status,
        PLACED_AT,
        allocatedAt,
        backorderedSince,
        null,
        null
    ));
  }

  private void persistOrderAt(UUID id, String sku, Instant placedAt) {
    jpaRepository.saveAndFlush(
        new OrderEntity(id, sku, 1, OrderStatus.PENDING, placedAt, null, null, null, null));
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = JpaOrderRepository.class)
  static class RepositoryConfiguration {
  }
}
