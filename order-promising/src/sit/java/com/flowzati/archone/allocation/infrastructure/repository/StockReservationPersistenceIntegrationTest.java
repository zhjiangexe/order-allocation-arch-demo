package com.flowzati.archone.allocation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockReservationRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Date;
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

/**
 * 預留的持久化。
 *
 * <p><b>粒度是訂單行 × 批次</b>，不是訂單——外鍵指向 {@code order_lines}，唯一鍵是
 * {@code (order_line_id, stock_pool_id)}。所以這裡的主角是**行的 id**，不是訂單的。
 */
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
  /** 這些測試都圍繞同一張單——以訂單查預留才查得到，那正是取消釋放走的路徑。 */
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final UUID STOCK_POOL_ID = uuid(10);
  /** 第二個批次：一條行跨兩批時要用到，也是唯一鍵「行 × 批」的另一半。 */
  private static final UUID SECOND_STOCK_POOL_ID = uuid(20);
  private static final String SKU = "SKU-1";

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
    UUID lineId = persistReferences(uuid(11));
    StockReservation reservation = StockReservation.create(
        reservationId,
        ORDER_ID, lineId, STOCK_POOL_ID, 3, RESERVED_AT);

    repositoryAdapter.save(reservation);
    jpaRepository.flush();
    entityManager.clear();

    StockReservation restored = onlyActiveForOrder();

    assertThat(restored.getId()).isEqualTo(reservationId);
    assertThat(restored.getOrderLineId()).isEqualTo(lineId);
    assertThat(restored.getStockPoolId()).isEqualTo(STOCK_POOL_ID);
    assertThat(restored.getQuantity()).isEqualTo(3);
    assertThat(restored.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(restored.getReservedAt()).isEqualTo(RESERVED_AT);
    assertThat(restored.getReleasedAt()).isNull();
    assertThat(restored.getVersion()).isZero();
  }

  @Test
  @DisplayName("一條行跨兩個批次時應存得下兩筆預留")
  void persistsTwoReservationsForOneLineAcrossTwoBatches() {
    UUID lineId = persistReferences(uuid(11));

    repositoryAdapter.save(
        StockReservation.create(uuid(1), ORDER_ID, lineId, STOCK_POOL_ID, 60, RESERVED_AT));
    repositoryAdapter.save(
        StockReservation.create(uuid(2), ORDER_ID, lineId, SECOND_STOCK_POOL_ID, 20, RESERVED_AT));
    jpaRepository.flush();
    entityManager.clear();

    // 這是分批之後 schema 必須允許的事。舊的 UNIQUE (order_id) 會擋下第二筆，而那條規則
    // 表達的是「一張單一筆預留」——分批之後它不再成立。
    assertThat(repositoryAdapter.findActiveByOrderId(ORDER_ID))
        .hasSize(2)
        .extracting(StockReservation::getQuantity)
        .containsExactlyInAnyOrder(60, 20);
  }

  @Test
  @DisplayName("釋放後儲存應還原 RELEASED state 並遞增 version")
  void persistsReleasedReservation() {
    UUID reservationId = uuid(1);
    UUID lineId = persistReferences(uuid(11));
    repositoryAdapter.save(
        StockReservation.create(reservationId, ORDER_ID, lineId, STOCK_POOL_ID, 3, RESERVED_AT));
    jpaRepository.flush();
    entityManager.clear();

    StockReservation active = onlyActiveForOrder();
    Instant releasedAt = RESERVED_AT.plusSeconds(60);
    active.release(releasedAt);
    repositoryAdapter.save(active);
    jpaRepository.flush();
    entityManager.clear();

    StockReservationEntity entity = jpaRepository.findById(reservationId).orElseThrow();

    assertThat(repositoryAdapter.findActiveByOrderId(ORDER_ID)).isEmpty();
    assertThat(entity.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(entity.getReleasedAt()).isEqualTo(releasedAt);
    assertThat(entity.getVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("findActiveByOrderId 應只回傳 ACTIVE reservation")
  void findsOnlyActiveReservations() {
    UUID activeLineId = persistReferences(uuid(11));
    UUID releasedLineId = persistReferences(uuid(12));
    jpaRepository.saveAndFlush(new StockReservationEntity(
        uuid(1), ORDER_ID, activeLineId, STOCK_POOL_ID, 3, ReservationStatus.ACTIVE,
        RESERVED_AT, null, null
    ));
    jpaRepository.saveAndFlush(new StockReservationEntity(
        uuid(2), ORDER_ID, releasedLineId, STOCK_POOL_ID, 3, ReservationStatus.RELEASED,
        RESERVED_AT, RESERVED_AT.plusSeconds(60), null
    ));
    entityManager.clear();

    // 同一張單的兩條行，只有 ACTIVE 那筆回來——過濾在資料庫做，呼叫端不必自己挑。
    assertThat(repositoryAdapter.findActiveByOrderId(ORDER_ID))
        .extracting(StockReservation::getId)
        .containsExactly(uuid(1));
  }

  @Test
  @DisplayName("找不到 ACTIVE reservation 時應忠實回傳空清單")
  void returnsEmptyWhenNoActiveReservationExists() {
    assertThat(repositoryAdapter.findActiveByOrderId(uuid(11))).isEmpty();
  }

  @Test
  @DisplayName("傳入空清單時不應查資料庫，直接回空")
  void returnsEmptyWithoutQueryingForAnEmptyIdList() {
    // 一張沒有行的訂單在這個系統裡不存在，但呼叫端（ReleaseReservationUsecase）不該為了
    // 這件事多寫一個 if——空的 IN (...) 在某些方言下是語法錯誤，所以擋在 adapter 裡。
    assertThat(repositoryAdapter.findActiveByOrderId(uuid(99))).isEmpty();
  }

  @Test
  @DisplayName("stale reservation 寫回時應被 optimistic locking 拒絕")
  void rejectsStaleVersion() {
    UUID reservationId = uuid(1);
    UUID lineId = persistReferences(uuid(11));
    jpaRepository.saveAndFlush(new StockReservationEntity(
        reservationId, ORDER_ID, lineId, STOCK_POOL_ID, 3, ReservationStatus.ACTIVE,
        RESERVED_AT, null, null
    ));
    entityManager.clear();
    StockReservation staleReservation = onlyActiveForOrder();

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
  @DisplayName("資料庫應拒絕同一條行對同一批的第二筆 reservation")
  void rejectsDuplicateReservationForTheSameLineAndBatch() {
    UUID lineId = persistReferences(uuid(11));
    insertReservation(uuid(1), lineId, STOCK_POOL_ID, 1, "ACTIVE", null);

    // 唯一鍵是「行 × 批」而不是「行」：同一條行對**不同**批的第二筆是合法的（見上面那支
    // 測試），對**同一**批的第二筆才是重複。
    assertThatThrownBy(() -> insertReservation(
        uuid(2), lineId, STOCK_POOL_ID, 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_stock_reservations_line_pool");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("資料庫應拒絕非正數 reservation quantity")
  void rejectsNonPositiveQuantity(int quantity) {
    UUID lineId = persistReferences(uuid(11));

    assertThatThrownBy(() -> insertReservation(
        uuid(1), lineId, STOCK_POOL_ID, quantity, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_stock_reservations_quantity_positive");
  }

  @Test
  @DisplayName("資料庫應接受 CONSUMED——R4 的 view 會用它做「已滿足」的謂詞")
  void acceptsConsumedStatus() {
    UUID lineId = persistReferences(uuid(11));

    // 本階段不產生這個狀態，但 CHECK 必須現在就接受它，否則 R7 開始出貨時才會發現。
    insertReservation(uuid(1), lineId, STOCK_POOL_ID, 1, "CONSUMED", null);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM stock_reservations WHERE id = ?", String.class, uuid(1)))
        .isEqualTo("CONSUMED");
  }

  @Test
  @DisplayName("資料庫應拒絕未知 reservation status")
  void rejectsUnknownStatus() {
    UUID lineId = persistReferences(uuid(11));

    assertThatThrownBy(() -> insertReservation(
        uuid(1), lineId, STOCK_POOL_ID, 1, "UNKNOWN", null
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("資料庫應拒絕不一致的 released state")
  void rejectsInconsistentReleasedState() {
    UUID lineId = persistReferences(uuid(11));

    assertThatThrownBy(() -> insertReservation(
        uuid(1), lineId, STOCK_POOL_ID, 1, "RELEASED", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_stock_reservations_released_state");
  }

  @Test
  @DisplayName("資料庫應拒絕不存在的 order line reference")
  void rejectsInvalidOrderLineForeignKey() {
    persistReferences(uuid(11));

    assertThatThrownBy(() -> insertReservation(
        uuid(1), uuid(99), STOCK_POOL_ID, 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_stock_reservations_order_line");
  }

  @Test
  @DisplayName("資料庫應拒絕不存在的 StockPool reference")
  void rejectsInvalidStockPoolForeignKey() {
    UUID lineId = persistReferences(uuid(11));

    assertThatThrownBy(() -> insertReservation(
        uuid(1), lineId, uuid(99), 1, "ACTIVE", null
    )).isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_stock_reservations_stock_pool");
  }

  /** 這張單目前唯一的有效預留。以訂單查，那是取消釋放走的路徑。 */
  private StockReservation onlyActiveForOrder() {
    List<StockReservation> active = repositoryAdapter.findActiveByOrderId(ORDER_ID);
    assertThat(active).hasSize(1);
    return active.getFirst();
  }

  /**
   * 種下這筆預留需要的所有參照，回傳**訂單行的 id**。
   *
   * <p>回行的 id 而不是訂單的：預留的外鍵指向 {@code order_lines}，測試要的就是那個值。
   */
  private UUID persistReferences(UUID orderId) {
    // 訂單行與庫存列的 (owner_id, sku_code) 都有外鍵指向主檔，因此主檔要先種。
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, SKU);
    insertStockPool(STOCK_POOL_ID, StockFixtures.EXPIRES_ON);
    // 第二批：同貨主同倉同 SKU 同入庫日，只有效期不同，所以是另一列。
    insertStockPool(SECOND_STOCK_POOL_ID, StockFixtures.EXPIRES_ON.plusMonths(6));
    jdbcTemplate.update("""
        INSERT INTO orders (
            id, owner_id, external_order_no, fulfillment_node_id, ship_to_zone, ship_to_address,
            promised_delivery_date, status, received_at)
        VALUES (?, ?, ?, ?, '100', '台北市中正區重慶南路一段 122 號', DATE '2026-08-01',
                'PENDING', ?)
        ON CONFLICT (id) DO NOTHING
        """, orderId, OrderFixtures.OWNER_ID, "EXT-" + orderId, OrderFixtures.NODE_ID,
        Timestamp.from(RESERVED_AT));
    UUID lineId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity, status)
        VALUES (?, ?, 1, ?, ?, 1, 'PENDING')
        """, lineId, orderId, OrderFixtures.OWNER_ID, SKU);
    return lineId;
  }

  private void insertStockPool(UUID id, java.time.LocalDate expiryDate) {
    jdbcTemplate.update("""
        INSERT INTO stock_pools (
            id, owner_id, node_id, sku_code, in_date, expiry_date,
            on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, ?, ?, ?, 100, 0)
        ON CONFLICT (id) DO NOTHING
        """, id, OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU,
        Date.valueOf(StockFixtures.ARRIVED_ON), Date.valueOf(expiryDate));
  }

  private void insertReservation(
      UUID id,
      UUID orderLineId,
      UUID stockPoolId,
      int quantity,
      String status,
      Instant releasedAt
  ) {
    jdbcTemplate.update("""
        INSERT INTO stock_reservations (
            id, order_id, order_line_id, stock_pool_id, quantity, status, reserved_at, released_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        ORDER_ID,
        orderLineId,
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
