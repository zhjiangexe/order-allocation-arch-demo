package com.flowzati.archone.allocation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.infrastructure.entity.StockPoolEntity;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 批次庫存的持久化。
 *
 * <p><b>一列是一批貨</b>，身分是五個維度的組合。這裡驗的是那組維度真的被資料庫當成身分（唯一
 * 鍵）、跨貨主的錯誤組合真的寫不進去（外鍵），以及配貨查詢的兩個篩選與三層排序真的在資料庫
 * 做完（含索引順序與 {@code ORDER BY} 一致，沒有額外的 Sort）。
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
    StockPoolRepositoryImpl.class,
    StockPoolPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("StockPool PostgreSQL persistence adapter")
class StockPoolPersistenceIntegrationTest {

  private static final Instant OLD_UPDATED_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final UUID STOCK_POOL_ID = uuid(1);
  // 排序是 node_id ASC，所以這個值必須大於 OrderFixtures.NODE_ID（…b1），
  // 否則「第一個倉先出現」的斷言就與被驗證的規則無關了。
  private static final UUID SECOND_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final String SKU = "SKU-1";
  private static final LocalDate TODAY = StockFixtures.TODAY;

  @Autowired
  private JpaStockRepository jpaRepository;

  @Autowired
  private StockPoolRepositoryImpl repositoryAdapter;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedCatalog() {
    // stock_pools 的 (owner_id, sku_code) 有外鍵指向 skus，所以主檔要先存在。
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, SKU, "SKU-2");
  }

  @Test
  @DisplayName("應還原完整的五個身分維度與兩個數量")
  void persistsAndRestoresEveryIdentityDimension() {
    StockPoolEntity saved = persistBatch(STOCK_POOL_ID, StockFixtures.EXPIRES_ON, 10, 4);

    StockPool byId = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();
    StockPool byIdentity = repositoryAdapter.findByIdentity(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON).orElseThrow();

    assertThat(byId.getOwnerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(byId.getNodeId()).isEqualTo(OrderFixtures.NODE_ID);
    assertThat(byId.getSkuCode()).isEqualTo(SKU);
    assertThat(byId.getInDate()).isEqualTo(StockFixtures.ARRIVED_ON);
    assertThat(byId.getExpiryDate()).isEqualTo(StockFixtures.EXPIRES_ON);
    assertThat(byId.getOnHandQuantity()).isEqualTo(10);
    assertThat(byId.getReservedQuantity()).isEqualTo(4);
    assertThat(byId.availableToPromise()).isEqualTo(6);
    assertThat(byId.getVersion()).isEqualTo(saved.getVersion());
    // 依五維鍵查與依 id 查必須回同一列——那是「五個維度就是身分」的意思。
    assertThat(byIdentity.getId()).isEqualTo(STOCK_POOL_ID);
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("配貨查詢應依 (效期, 入庫日, id) 排序")
  void returnsAllocatableBatchesInFefoOrder() {
    UUID nearer = uuid(2);
    UUID tieEarlyArrival = uuid(3);
    UUID tieLateArrival = uuid(4);
    // 刻意以「與期望相反」的順序寫入，確保順序來自 ORDER BY 而不是插入次序。
    persistBatch(tieLateArrival, TODAY.plusMonths(6), TODAY.minusMonths(1), 30, 0);
    persistBatch(tieEarlyArrival, TODAY.plusMonths(6), TODAY.minusMonths(2), 40, 0);
    persistBatch(nearer, TODAY.plusMonths(1), TODAY.minusMonths(2), 60, 0);

    assertThat(allocatable())
        .extracting(StockPool::getId)
        .containsExactly(nearer, tieEarlyArrival, tieLateArrival);
  }

  @Test
  @DisplayName("配貨查詢應同時濾掉已過期與已被預留光的批")
  void excludesExpiredAndFullyReservedBatches() {
    UUID usable = uuid(2);
    persistBatch(usable, TODAY.plusMonths(1), 10, 0);
    persistBatch(uuid(3), TODAY.minusDays(1), 25, 0);              // 已過期
    persistBatch(uuid(4), TODAY.plusMonths(2), 40, 40);            // 沒過期但預留光
    persistBatch(uuid(5), TODAY, 5, 0);                            // 效期當天，仍可配

    // 兩個條件缺一不可。少了效期那條會把過期貨配出去；少了數量那條則每個呼叫端都得自己
    // 記得跳過 ATP 為 0 的批，而方法名承諾的是「配得到的」。
    assertThat(allocatable())
        .extracting(StockPool::getId)
        .containsExactly(uuid(5), usable);
  }

  @Test
  @DisplayName("庫存頁查詢應跨倉回全部，含過期與預留光的批")
  void returnsEveryBatchAcrossNodesForTheStockPage() {
    persistBatch(uuid(2), TODAY.plusMonths(1), 10, 0);
    persistBatch(uuid(3), TODAY.minusDays(1), 25, 0);
    persistBatch(uuid(4), TODAY.plusMonths(2), 40, 40);

    // 過期與預留光的都要在——濾掉會讓「有貨但出不了」與「什麼都沒有」在畫面上長得一樣。
    assertThat(repositoryAdapter.findBatchesAcrossNodes(OrderFixtures.OWNER_ID, SKU))
        .hasSize(3);
  }

  @Test
  @DisplayName("庫存頁查詢應先依倉別分組，組內再依 (效期, 入庫日, id) 排序")
  void groupsStockPageBatchesByNodeThenFefoWithinEachNode() {
    insertNode(SECOND_NODE_ID, "WH-TEST-2", "第二測試倉");
    UUID firstNodeLater = uuid(2);
    UUID firstNodeEarlier = uuid(3);
    UUID secondNodeLater = uuid(4);
    UUID secondNodeEarlier = uuid(5);
    // 以「與期望完全相反」的順序寫入，確保順序來自 ORDER BY 而不是插入次序。
    persistBatchAtNode(SECOND_NODE_ID, secondNodeLater, TODAY.plusMonths(6), 10, 0);
    persistBatchAtNode(SECOND_NODE_ID, secondNodeEarlier, TODAY.plusMonths(1), 10, 0);
    persistBatchAtNode(OrderFixtures.NODE_ID, firstNodeLater, TODAY.plusMonths(6), 10, 0);
    persistBatchAtNode(OrderFixtures.NODE_ID, firstNodeEarlier, TODAY.plusMonths(1), 10, 0);

    // **倉別必須先分組。** 配貨一次只在一個倉裡進行（FEFO 查詢帶 nodeId），所以跨倉依效期
    // 混排會在畫面上顯示一個永遠不會發生的取用順序。組內才是真正的取用順序。
    //
    // 這個順序是查詢端點的保證，不是呼叫端的責任：tie-break 一路到 id，而 id 存在的目的
    // 是讓順序可重現、本身不帶任何呼叫端排得出來的意義。
    assertThat(repositoryAdapter.findBatchesAcrossNodes(OrderFixtures.OWNER_ID, SKU))
        .extracting(StockPool::getId)
        .containsExactly(firstNodeEarlier, firstNodeLater, secondNodeEarlier, secondNodeLater);
  }

  @Test
  @DisplayName("FEFO 查詢應沿索引取列，不得出現額外的 Sort 節點")
  void fefoQueryScansTheIndexWithoutSorting() {
    persistBatch(uuid(2), TODAY.plusMonths(1), 10, 0);

    // 關掉 seq scan 才問得出「索引欄位順序與 ORDER BY 是否一致」——資料量小的時候
    // PostgreSQL 一定選 seq scan，那樣這個問題根本沒被問到。
    jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
    String plan = String.join("\n", jdbcTemplate.queryForList("""
        EXPLAIN SELECT * FROM stock_pools
        WHERE owner_id = ? AND node_id = ? AND sku_code = ? AND expiry_date >= ?
          AND on_hand_quantity > reserved_quantity
        ORDER BY expiry_date, in_date, id
        """, String.class,
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, Date.valueOf(TODAY)));

    assertThat(plan).contains("idx_stock_pools_fefo");
    // 出現 Sort 就代表索引的欄位順序與 ORDER BY 不一致，資料庫得把結果再排一次。
    assertThat(plan).doesNotContain("Sort");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @EnumSource(QuantityMutation.class)
  @DisplayName("reserve、release 與 replenish 儲存時都應更新 timestamp 與 version")
  void updatesTimestampAndVersionForEveryQuantityMutation(QuantityMutation mutation) {
    StockPoolEntity initial = persistBatch(STOCK_POOL_ID, StockFixtures.EXPIRES_ON, 10, 2);
    Long initialVersion = initial.getVersion();
    setOldUpdatedAt(STOCK_POOL_ID);

    StockPool stockPool = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();
    mutation.apply(stockPool);

    repositoryAdapter.save(stockPool);
    jpaRepository.flush();
    entityManager.clear();

    StockPoolEntity updated = jpaRepository.findById(STOCK_POOL_ID).orElseThrow();
    assertThat(updated.getOnHandQuantity()).isEqualTo(mutation.expectedOnHandQuantity);
    assertThat(updated.getReservedQuantity()).isEqualTo(mutation.expectedReservedQuantity);
    assertThat(updated.getUpdatedAt()).isAfter(OLD_UPDATED_AT);
    assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
  }

  @Test
  @DisplayName("stale StockPool snapshot 寫回時應被 optimistic locking 拒絕")
  void rejectsStaleVersion() {
    persistBatch(STOCK_POOL_ID, StockFixtures.EXPIRES_ON, 10, 2);
    StockPool staleStockPool = repositoryAdapter.findById(STOCK_POOL_ID).orElseThrow();

    // 模擬另一個 transaction 已先更新同一筆並遞增 version。
    jdbcTemplate.update("""
        UPDATE stock_pools
        SET on_hand_quantity = 11,
            version = version + 1
        WHERE id = ?
        """, STOCK_POOL_ID);
    entityManager.clear();
    staleStockPool.reserve(1);

    assertThatThrownBy(() -> {
      repositoryAdapter.save(staleStockPool);
      jpaRepository.flush();
    }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("資料庫應拒絕五個維度全等的第二列")
  void rejectsDuplicateFiveDimensionKey() {
    insertRawBatch(STOCK_POOL_ID, OrderFixtures.OWNER_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 10, 0);

    assertThatThrownBy(() -> insertRawBatch(uuid(9), OrderFixtures.OWNER_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 10, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_stock_pools_batch");
  }

  @ParameterizedTest(name = "[{index}] 只差 {0}")
  @MethodSource("distinctDimensions")
  @DisplayName("五個維度只要有一個不同，就是不同的一批")
  void treatsAnyDifferingDimensionAsADifferentBatch(
      String dimension, String skuCode, LocalDate inDate, LocalDate expiryDate) {
    insertRawBatch(STOCK_POOL_ID, OrderFixtures.OWNER_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 10, 0);

    // 不合併是重點：少任何一個維度都會讓不可互換的貨被併成一列，而那是安靜發生的。
    insertRawBatch(uuid(9), OrderFixtures.OWNER_ID, skuCode, inDate, expiryDate, 10, 0);

    assertThat(jpaRepository.count()).isEqualTo(2);
  }

  static Stream<Arguments> distinctDimensions() {
    return Stream.of(
        Arguments.of("SKU", "SKU-2", StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON),
        Arguments.of("入庫日", SKU, StockFixtures.ARRIVED_ON.plusDays(1),
            StockFixtures.EXPIRES_ON),
        Arguments.of("效期", SKU, StockFixtures.ARRIVED_ON,
            StockFixtures.EXPIRES_ON.plusDays(1))
    );
  }

  @Test
  @DisplayName("資料庫應拒絕指向該貨主沒有的 SKU 代碼")
  void rejectsStockNamingAnotherOwnersSkuCode() {
    // SKU-1 只登記在 OWNER_ID 名下。跨貨主的組合建不起來，因為外鍵帶著貨主。
    assertThatThrownBy(() -> insertRawBatch(STOCK_POOL_ID, OrderFixtures.OTHER_OWNER_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 10, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_stock_pools_sku");
  }

  @Test
  @DisplayName("資料庫應接受到貨時就已過期的批——刻意沒有 expiry >= in_date 的約束")
  void acceptsStockThatArrivedAlreadyExpired() {
    // 運輸延誤、上游出錯、長途轉運都會造成這件事，而倉庫實體上就是收到了那批貨。
    // 加上那個約束，要把它記進系統就得在兩個日期裡挑一個造假。
    insertRawBatch(STOCK_POOL_ID, OrderFixtures.OWNER_ID, SKU,
        TODAY, TODAY.minusMonths(1), 25, 0);

    assertThat(repositoryAdapter.findById(STOCK_POOL_ID)).isPresent();
    assertThat(allocatable()).isEmpty();
  }

  @ParameterizedTest(name = "[{index}] onHand={0}, reserved={1}, constraint={2}")
  @MethodSource("invalidDatabaseQuantities")
  @DisplayName("資料庫應拒絕破壞 StockPool quantity invariants 的資料")
  void rejectsInvalidQuantities(
      int onHandQuantity,
      int reservedQuantity,
      String expectedConstraint
  ) {
    assertThatThrownBy(() -> insertRawBatch(STOCK_POOL_ID, OrderFixtures.OWNER_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, onHandQuantity, reservedQuantity))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining(expectedConstraint);
  }

  static Stream<Arguments> invalidDatabaseQuantities() {
    return Stream.of(
        Arguments.of(-1, 0, "ck_stock_pools_on_hand_non_negative"),
        Arguments.of(1, -1, "ck_stock_pools_reserved_non_negative"),
        Arguments.of(1, 2, "ck_stock_pools_reserved_not_above_on_hand")
    );
  }

  private List<StockPool> allocatable() {
    return repositoryAdapter.findAllocatableBatchesInFefoOrder(
        OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU, TODAY);
  }

  private StockPoolEntity persistBatch(
      UUID id, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
    return persistBatch(id, expiryDate, StockFixtures.ARRIVED_ON, onHandQuantity,
        reservedQuantity);
  }

  private StockPoolEntity persistBatch(
      UUID id, LocalDate expiryDate, LocalDate inDate, int onHandQuantity,
      int reservedQuantity) {
    return persistBatchAtNode(OrderFixtures.NODE_ID, id, expiryDate, inDate, onHandQuantity,
        reservedQuantity);
  }

  private StockPoolEntity persistBatchAtNode(
      UUID nodeId, UUID id, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
    return persistBatchAtNode(nodeId, id, expiryDate, StockFixtures.ARRIVED_ON, onHandQuantity,
        reservedQuantity);
  }

  private StockPoolEntity persistBatchAtNode(
      UUID nodeId, UUID id, LocalDate expiryDate, LocalDate inDate, int onHandQuantity,
      int reservedQuantity) {
    StockPoolEntity saved = jpaRepository.saveAndFlush(new StockPoolEntity(
        id, OrderFixtures.OWNER_ID, nodeId, SKU, inDate, expiryDate,
        onHandQuantity, reservedQuantity, null));
    entityManager.clear();
    return saved;
  }

  /** {@code stock_pools.node_id} 外鍵指向 {@code fulfillment_nodes}，所以倉要先存在。 */
  private void insertNode(UUID nodeId, String code, String name) {
    jdbcTemplate.update("""
        INSERT INTO fulfillment_nodes (id, code, name)
        VALUES (?, ?, ?)
        """, nodeId, code, name);
  }

  private void setOldUpdatedAt(UUID id) {
    jdbcTemplate.update(
        "UPDATE stock_pools SET updated_at = ? WHERE id = ?",
        Timestamp.from(OLD_UPDATED_AT),
        id
    );
    entityManager.clear();
  }

  private void insertRawBatch(
      UUID id, UUID ownerId, String skuCode, LocalDate inDate, LocalDate expiryDate,
      int onHandQuantity, int reservedQuantity) {
    jdbcTemplate.update("""
        INSERT INTO stock_pools (
            id, owner_id, node_id, sku_code, in_date, expiry_date,
            on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, id, ownerId, OrderFixtures.NODE_ID, skuCode,
        Date.valueOf(inDate), Date.valueOf(expiryDate), onHandQuantity, reservedQuantity);
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  enum QuantityMutation {
    RESERVE(10, 3) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.reserve(1);
      }
    },
    RELEASE(10, 1) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.release(1);
      }
    },
    REPLENISH(11, 2) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.replenish(1);
      }
    },
    CONSUME(9, 1) {
      @Override
      void apply(StockPool stockPool) {
        stockPool.consume(1);
      }
    };

    private final int expectedOnHandQuantity;
    private final int expectedReservedQuantity;

    QuantityMutation(int expectedOnHandQuantity, int expectedReservedQuantity) {
      this.expectedOnHandQuantity = expectedOnHandQuantity;
      this.expectedReservedQuantity = expectedReservedQuantity;
    }

    abstract void apply(StockPool stockPool);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = JpaStockRepository.class)
  static class RepositoryConfiguration {
  }
}
