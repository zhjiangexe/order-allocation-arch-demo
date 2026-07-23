package com.flowzati.archone.allocation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockReservationRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
    StockReservationRepositoryImpl.class,
    StockReservationPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("StockReservation PostgreSQL persistence adapter")
class StockReservationPersistenceIntegrationTest {

  private static final Instant RESERVED_AT = Instant.parse("2026-07-24T08:00:00Z");
  private static final long STOCK_POOL_ID = 10L;

  @Autowired
  private JpaStockReservationRepository jpaRepository;

  @Autowired
  private StockReservationRepositoryImpl repositoryAdapter;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("應寫入並還原完整 ACTIVE reservation 與 version")
  void persistsAndRestoresActiveReservation() {
    UUID reservationId = uuid(1);
    UUID orderId = uuid(11);
    persistReferences(orderId);
    StockReservation reservation = StockReservation.create(
        reservationId,
        orderId,
        STOCK_POOL_ID,
        3,
        RESERVED_AT
    );

    repositoryAdapter.save(reservation);
    jpaRepository.flush();
    entityManager.clear();

    StockReservation restored = repositoryAdapter.findActiveByOrderId(orderId).orElseThrow();

    assertThat(restored.getId()).isEqualTo(reservationId);
    assertThat(restored.getOrderId()).isEqualTo(orderId);
    assertThat(restored.getStockPoolId()).isEqualTo(STOCK_POOL_ID);
    assertThat(restored.getQuantity()).isEqualTo(3);
    assertThat(restored.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(restored.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(restored.getReleasedAt()).isNull();
    assertThat(restored.getVersion()).isZero();
  }

  @Test
  @DisplayName("釋放後儲存應還原 RELEASED state 並遞增 version")
  void persistsReleasedReservation() {
    UUID reservationId = uuid(1);
    UUID orderId = uuid(11);
    persistReferences(orderId);
    StockReservation reservation = StockReservation.create(
        reservationId,
        orderId,
        STOCK_POOL_ID,
        3,
        RESERVED_AT
    );
    repositoryAdapter.save(reservation);
    jpaRepository.flush();
    entityManager.clear();

    StockReservation active = repositoryAdapter.findActiveByOrderId(orderId).orElseThrow();
    Instant releasedAt = RESERVED_AT.plusSeconds(60);
    active.release(releasedAt);
    repositoryAdapter.save(active);
    jpaRepository.flush();
    entityManager.clear();

    Optional<StockReservation> activeResult = repositoryAdapter.findActiveByOrderId(orderId);
    StockReservationEntity entity = jpaRepository.findById(reservationId).orElseThrow();

    assertThat(activeResult).isEmpty();
    assertThat(entity.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(entity.getReleasedAt()).isEqualTo(releasedAt);
    assertThat(entity.getVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("findActiveByOrderId 應只回傳 ACTIVE reservation")
  void findsOnlyActiveReservationByOrderId() {
    UUID activeOrderId = uuid(11);
    UUID releasedOrderId = uuid(12);
    persistReferences(activeOrderId);
    persistReferences(releasedOrderId);
    jpaRepository.saveAndFlush(new StockReservationEntity(
        uuid(1), activeOrderId, STOCK_POOL_ID, 3, ReservationStatus.ACTIVE,
        RESERVED_AT, null, null
    ));
    jpaRepository.saveAndFlush(new StockReservationEntity(
        uuid(2), releasedOrderId, STOCK_POOL_ID, 3, ReservationStatus.RELEASED,
        RESERVED_AT, RESERVED_AT.plusSeconds(60), null
    ));
    entityManager.clear();

    Optional<StockReservation> active = repositoryAdapter.findActiveByOrderId(activeOrderId);
    Optional<StockReservation> released = repositoryAdapter.findActiveByOrderId(releasedOrderId);

    assertThat(active).map(StockReservation::getId).contains(uuid(1));
    assertThat(released).isEmpty();
  }

  @Test
  @DisplayName("找不到 ACTIVE reservation 時應忠實回傳 empty Optional")
  void returnsEmptyWhenActiveReservationDoesNotExist() {
    assertThat(repositoryAdapter.findActiveByOrderId(uuid(11))).isEmpty();
  }

  @Test
  @DisplayName("stale reservation 寫回時應被 optimistic locking 拒絕")
  void rejectsStaleVersion() {
    UUID reservationId = uuid(1);
    UUID orderId = uuid(11);
    persistReferences(orderId);
    jpaRepository.saveAndFlush(new StockReservationEntity(
        reservationId, orderId, STOCK_POOL_ID, 3, ReservationStatus.ACTIVE,
        RESERVED_AT, null, null
    ));
    entityManager.clear();
    StockReservation staleReservation = repositoryAdapter.findActiveByOrderId(orderId).orElseThrow();

    jdbcTemplate.update(
        "UPDATE stock_reservations SET version = version + 1 WHERE id = ?",
        reservationId
    );
    entityManager.clear();
    staleReservation.release(RESERVED_AT.plusSeconds(60));

    assertThatThrownBy(() -> {
      repositoryAdapter.save(staleReservation);
      jpaRepository.flush();
    }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("資料庫應拒絕同一 order 的第二筆 reservation")
  void rejectsDuplicateOrderReservation() {
    UUID orderId = uuid(11);
    persistReferences(orderId);
    insertReservation(uuid(1), orderId, STOCK_POOL_ID, 1, "ACTIVE", null);

    assertThatThrownBy(() -> insertReservation(
        uuid(2), orderId, STOCK_POOL_ID, 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_stock_reservations_order_id");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("資料庫應拒絕非正數 reservation quantity")
  void rejectsNonPositiveQuantity(int quantity) {
    UUID orderId = uuid(11);
    persistReferences(orderId);

    assertThatThrownBy(() -> insertReservation(
        uuid(1), orderId, STOCK_POOL_ID, quantity, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_stock_reservations_quantity_positive");
  }

  @Test
  @DisplayName("資料庫應拒絕未知 reservation status")
  void rejectsUnknownStatus() {
    UUID orderId = uuid(11);
    persistReferences(orderId);

    assertThatThrownBy(() -> insertReservation(
        uuid(1), orderId, STOCK_POOL_ID, 1, "UNKNOWN", null
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("資料庫應拒絕不一致的 released state")
  void rejectsInconsistentReleasedState() {
    UUID orderId = uuid(11);
    persistReferences(orderId);

    assertThatThrownBy(() -> insertReservation(
        uuid(1), orderId, STOCK_POOL_ID, 1, "RELEASED", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_stock_reservations_released_state");
  }

  @Test
  @DisplayName("資料庫應拒絕不存在的 Order reference")
  void rejectsInvalidOrderForeignKey() {
    UUID existingOrderId = uuid(11);
    persistReferences(existingOrderId);

    assertThatThrownBy(() -> insertReservation(
        uuid(1), uuid(99), STOCK_POOL_ID, 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_stock_reservations_order");
  }

  @Test
  @DisplayName("資料庫應拒絕不存在的 StockPool reference")
  void rejectsInvalidStockPoolForeignKey() {
    UUID existingOrderId = uuid(11);
    persistReferences(existingOrderId);

    assertThatThrownBy(() -> insertReservation(
        uuid(1), existingOrderId, 99L, 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_stock_reservations_stock_pool");
  }

  private void persistReferences(UUID orderId) {
    jdbcTemplate.update("""
        INSERT INTO stock_pools (id, sku, on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """, STOCK_POOL_ID, "SKU-1", 10, 0);
    jdbcTemplate.update("""
        INSERT INTO orders (id, sku, quantity, status, placed_at)
        VALUES (?, ?, ?, ?, ?)
        """, orderId, "SKU-1", 1, "PENDING", Timestamp.from(RESERVED_AT));
  }

  private void insertReservation(
      UUID id,
      UUID orderId,
      long stockPoolId,
      int quantity,
      String status,
      Instant releasedAt
  ) {
    jdbcTemplate.update("""
        INSERT INTO stock_reservations (
            id, order_id, stock_pool_id, quantity, status, reserved_at, released_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        orderId,
        stockPoolId,
        quantity,
        status,
        Timestamp.from(RESERVED_AT),
        releasedAt == null ? null : Timestamp.from(releasedAt)
    );
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = JpaStockReservationRepository.class)
  static class RepositoryConfiguration {
  }
}
