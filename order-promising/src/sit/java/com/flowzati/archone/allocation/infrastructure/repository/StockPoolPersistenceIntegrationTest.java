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
import java.util.Map;
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
  // 排序是 location_id ASC，所以這個值必須大於 OrderFixtures.LOCATION_ID（…c1），
  // 否則「第一個位置先出現」的斷言就與被驗證的規則無關了。
  private static final UUID SECOND_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID SECOND_LOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000c2");
  private static final String SKU = "SKU-1";
  /** 分組要驗得出「同一個倉的兩個 SKU 各自成組」，所以需要第二個代碼。 */
  private static final String SECOND_SKU = "SKU-2";
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
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON).orElseThrow();

    assertThat(byId.getOwnerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(byId.getLocationId()).isEqualTo(OrderFixtures.LOCATION_ID);
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
  @DisplayName("庫存頁查詢應回這個倉的全部批，含過期與預留光的")
  void returnsEveryBatchHeldInTheWarehouse() {
    persistBatch(uuid(2), TODAY.plusMonths(1), 10, 0);
    persistBatch(uuid(3), TODAY.minusDays(1), 25, 0);
    persistBatch(uuid(4), TODAY.plusMonths(2), 40, 40);

    // 過期與預留光的都要在——濾掉會讓「有貨但出不了」與「什麼都沒有」在畫面上長得一樣。
    assertThat(repositoryAdapter.findBatchesInLocation(OrderFixtures.OWNER_ID,
        OrderFixtures.LOCATION_ID))
        .hasEntrySatisfying(SKU, batches -> assertThat(batches).hasSize(3));
  }

  @Test
  @DisplayName("庫存頁查詢應依 SKU 分組，組內再依 (效期, 入庫日, id) 排序")
  void groupsWarehouseBatchesBySkuAndOrdersEachGroupByFefo() {
    UUID firstSkuLater = uuid(2);
    UUID firstSkuEarlier = uuid(3);
    UUID secondSkuLater = uuid(4);
    UUID secondSkuEarlier = uuid(5);
    // 以「與期望完全相反」的順序寫入，確保順序來自 ORDER BY 而不是插入次序。
    persistBatchOfSku(SECOND_SKU, secondSkuLater, TODAY.plusMonths(6));
    persistBatchOfSku(SECOND_SKU, secondSkuEarlier, TODAY.plusMonths(1));
    persistBatchOfSku(SKU, firstSkuLater, TODAY.plusMonths(6));
    persistBatchOfSku(SKU, firstSkuEarlier, TODAY.plusMonths(1));

    Map<String, List<StockPool>> held = repositoryAdapter.findBatchesInLocation(
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID);

    // 組內才是真正的取用順序。這個順序是查詢的保證而不是呼叫端的責任：tie-break 一路到 id，
    // 而 id 存在的目的是讓順序可重現、本身不帶任何呼叫端排得出來的意義。
    //
    // **鍵的順序也要斷言。** containsOnlyKeys 不看順序，而 Map.copyOf 的迭代順序未定義——
    // 只驗鍵的集合，等於讓「把排好的順序在最後一步丟掉」這個錯誤完全沒有訊號。
    assertThat(held.keySet()).containsExactly(SKU, SECOND_SKU);
    assertThat(held.get(SKU)).extracting(StockPool::getId)
        .containsExactly(firstSkuEarlier, firstSkuLater);
    assertThat(held.get(SECOND_SKU)).extracting(StockPool::getId)
        .containsExactly(secondSkuEarlier, secondSkuLater);
  }

  @Test
  @DisplayName("庫存頁查詢不得帶出別的倉的批——配貨從不跨倉")
  void excludesBatchesHeldInAnotherWarehouse() {
    insertNode(SECOND_NODE_ID, "WH-TEST-2", "第二測試倉");
    UUID here = uuid(2);
    persistBatchAtNode(OrderFixtures.LOCATION_ID, here, TODAY.plusMonths(1), 10, 0);
    persistBatchAtNode(SECOND_LOCATION_ID, uuid(3), TODAY.plusMonths(1), 10, 0);

    assertThat(repositoryAdapter.findBatchesInLocation(OrderFixtures.OWNER_ID,
        OrderFixtures.LOCATION_ID))
        .hasEntrySatisfying(SKU, batches ->
            assertThat(batches).extracting(StockPool::getId).containsExactly(here));
  }

  @Test
  @DisplayName("一批都沒有的倉應回空分組，不是例外——那是新倉上線時的正常狀態")
  void answersAnEmptyWarehouseWithAnEmptyGrouping() {
    insertNode(SECOND_NODE_ID, "WH-TEST-2", "第二測試倉");

    assertThat(repositoryAdapter.findBatchesInLocation(OrderFixtures.OWNER_ID, SECOND_NODE_ID))
        .isEmpty();
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
        WHERE owner_id = ? AND location_id = ? AND sku_code = ? AND expiry_date >= ?
          AND on_hand_quantity > reserved_quantity
        ORDER BY expiry_date, in_date, id
        """, String.class,
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, Date.valueOf(TODAY)));

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
        OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, SKU, TODAY);
  }

  private StockPoolEntity persistBatch(
      UUID id, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
    return persistBatch(id, expiryDate, StockFixtures.ARRIVED_ON, onHandQuantity,
        reservedQuantity);
  }

  private StockPoolEntity persistBatch(
      UUID id, LocalDate expiryDate, LocalDate inDate, int onHandQuantity,
      int reservedQuantity) {
    return persistBatchAtNode(OrderFixtures.LOCATION_ID, id, expiryDate, inDate, onHandQuantity,
        reservedQuantity);
  }

  private StockPoolEntity persistBatchOfSku(String skuCode, UUID id, LocalDate expiryDate) {
    StockPoolEntity saved = jpaRepository.saveAndFlush(new StockPoolEntity(
        id, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, skuCode, StockFixtures.ARRIVED_ON,
        expiryDate, 10, 0, null));
    entityManager.clear();
    return saved;
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

  /** {@code stock_pools.location_id} 外鍵指向 {@code stock_locations}，所以位置要先存在。 */
  /** 建倉，順帶建它的內部位置——庫存掛在位置上，少了它外鍵過不了。 */
  private void insertNode(UUID nodeId, String code, String name) {
    jdbcTemplate.update("""
        INSERT INTO fulfillment_nodes (id, code, name)
        VALUES (?, ?, ?)
        ON CONFLICT DO NOTHING
        """, nodeId, code, name);
    jdbcTemplate.update("""
        INSERT INTO stock_locations (id, warehouse_id, code, name, usage)
        VALUES (?, ?, ?, ?, 'INTERNAL')
        ON CONFLICT DO NOTHING
        """, SECOND_LOCATION_ID, nodeId, code + "/Stock", name + "／庫存");
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
            id, owner_id, location_id, sku_code, in_date, expiry_date,
            on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, id, ownerId, OrderFixtures.LOCATION_ID, skuCode,
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
