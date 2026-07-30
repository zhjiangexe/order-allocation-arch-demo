package com.flowzati.archone.bootstrap;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.common.time.BusinessCalendar;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import com.flowzati.archone.catalog.domain.repository.FulfillmentNodeRepository;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Import(PostgreSQLTestConfiguration.class)
class DevSeedDataIntegrationTest {

  @Autowired
  private DevSeedDataInitializer initializer;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockReservationRepository stockReservationRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private BusinessCalendar businessCalendar;

  @Autowired
  private OwnerRepository ownerRepository;

  @Autowired
  private FulfillmentNodeRepository fulfillmentNodeRepository;

  /**
   * 每支測試前重新 seed。
   *
   * <p>seed 本身是 {@code ApplicationRunner}，只在 context 啟動時跑一次；而下方的
   * {@code @AfterEach} 會清空資料庫，所以第二支之後的測試會看到空資料。這裡重跑一次，
   * 順帶讓「重跑不重複」這件事在每支測試都被走過一遍。
   */
  @BeforeEach
  void seedAgain() {
    initializer.run(null);
  }

  @AfterEach
  void clearDatabase() {
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM order_lines");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
    jdbcTemplate.execute("DELETE FROM skus");
    jdbcTemplate.execute("DELETE FROM products");
    jdbcTemplate.execute("DELETE FROM owner_nodes");
    jdbcTemplate.execute("DELETE FROM owners");
    jdbcTemplate.execute("DELETE FROM fulfillment_nodes");
  }

  @Test
  @DisplayName("dev seed 應建立一致資料且重跑不重複")
  void shouldCreateConsistentDevSeedDataWithoutDuplicatesOnRepeatRun() throws Exception {
    assertThat(batch(DevSeedDataInitializer.NEAR_EXPIRY_STOCK_POOL_ID)).satisfies(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(60);
      // 被跨批訂單全部吃掉：畫面上要有一個「配完的批」。
      assertThat(pool.getReservedQuantity()).isEqualTo(60);
    });
    assertThat(batch(DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID)).satisfies(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(40);
      // 配一半的批：跨批訂單的第二段。
      assertThat(pool.getReservedQuantity()).isEqualTo(20);
    });
    assertThat(batch(DevSeedDataInitializer.EMPTY_STOCK_POOL_ID)).satisfies(pool -> {
      assertThat(pool.getOnHandQuantity()).isZero();
      assertThat(pool.getReservedQuantity()).isZero();
    });
    assertThat(batch(DevSeedDataInitializer.PARTIALLY_RESERVED_STOCK_POOL_ID)).satisfies(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(20);
      assertThat(pool.getReservedQuantity()).isEqualTo(5);
    });
    assertThat(orderRepository.findById(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
        .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(activeReservationsOf(DevSeedDataInitializer.PARTIALLY_RESERVED_ORDER_ID))
        .singleElement().satisfies(reservation -> {
          assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
          assertThat(reservation.getQuantity()).isEqualTo(5);
          assertThat(reservation.getStockPoolId())
              .isEqualTo(DevSeedDataInitializer.PARTIALLY_RESERVED_STOCK_POOL_ID);
        });

    initializer.run(null);

    // 六批（近／中早／中晚／已過期／空／部分預留）、三張單、三筆預留。
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
        .isEqualTo(6);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
        .isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
        .isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM owners", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("種子必須有同效期不同入庫日的兩批——少了它，FEFO 的 tie-breaker 完全沒被測到")
  void seedsTwoBatchesSharingAnExpiryDateButDifferingInArrival() {
    StockPool early = batch(DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID);
    StockPool late = batch(DevSeedDataInitializer.MID_EXPIRY_LATE_ARRIVAL_STOCK_POOL_ID);

    assertThat(early.getExpiryDate()).isEqualTo(late.getExpiryDate());
    assertThat(early.getInDate()).isBefore(late.getInDate());
  }

  @Test
  @DisplayName("種子必須有一批已過期的貨——「有貨但配不到」在畫面上要看得見")
  void seedsAnExpiredBatchThatIsPresentButNotAllocatable() {
    StockPool expired = batch(DevSeedDataInitializer.EXPIRED_STOCK_POOL_ID);

    // 不刪除、不隱藏：倉庫裡真的有這 25 件，而它與「什麼都沒有」要引導出不同的動作。
    assertThat(expired.getOnHandQuantity()).isEqualTo(25);
    assertThat(expired.isExpired(businessCalendar.today())).isTrue();
    assertThat(stockPoolRepository.findAllocatableBatchesInFefoOrder(
        DevSeedDataInitializer.FIRST_OWNER_ID, DevSeedDataInitializer.NORTH_NODE_ID,
        DevSeedDataInitializer.AVAILABLE_SKU, businessCalendar.today()))
        .extracting(StockPool::getId)
        .doesNotContain(DevSeedDataInitializer.EXPIRED_STOCK_POOL_ID);
  }

  @Test
  @DisplayName("種子必須有一張需求跨兩批的訂單——多批取用與多筆預留唯一的資料來源")
  void seedsAnOrderWhoseDemandSpansTwoBatches() {
    assertThat(orderRepository.findById(DevSeedDataInitializer.SPANNING_ORDER_ID))
        .hasValueSatisfying(order -> {
          assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
          assertThat(order.getDemandFor(DevSeedDataInitializer.AVAILABLE_SKU)).isEqualTo(80);
        });

    // 80 件 = 近效期 60 + 中效期 20，所以是兩筆預留，各指向不同的批。
    assertThat(activeReservationsOf(DevSeedDataInitializer.SPANNING_ORDER_ID))
        .hasSize(2)
        .extracting(reservation -> reservation.getStockPoolId(),
            reservation -> reservation.getQuantity())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                DevSeedDataInitializer.NEAR_EXPIRY_STOCK_POOL_ID, 60),
            org.assertj.core.groups.Tuple.tuple(
                DevSeedDataInitializer.MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID, 20));
  }

  @Test
  @DisplayName("種子的缺貨訂單應真的在 FIFO 佇列裡——補貨要能喚醒它，否則它是一列死資料")
  void seedsABackorderThatReplenishmentCanActuallyWake() {
    // 這一條守的是「種子訂單不是 PENDING」。PENDING 在真實系統裡是收單到消費之間的過渡，
    // 固化成種子等於展示一個穩定狀態下不存在的東西；更糟的是那張單繞過下單 usecase 直接
    // 寫入、沒有 OrderPlaced 事件，配置端從不知道它存在，補貨也不會碰它（只處理
    // BACKORDERED）。照操作台 README 的 demo 流程補貨後畫面毫無變化，看起來像壞掉。
    List<Order> queue = orderRepository.findBackordersBySkuInFifoOrder(
        DevSeedDataInitializer.SECOND_OWNER_ID, DevSeedDataInitializer.EMPTY_SKU, 1_000);

    assertThat(queue).extracting(Order::getId)
        .containsExactly(DevSeedDataInitializer.BACKORDERED_ORDER_ID);
    assertThat(queue.getFirst().getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(queue.getFirst().getBackOrderedSince()).isNotNull();

    // 缺貨對象的庫存池必須真的是空的，否則「試過、沒貨」這個狀態自相矛盾
    assertThat(batch(DevSeedDataInitializer.EMPTY_STOCK_POOL_ID).availableToPromise()).isZero();
  }

  @Test
  @DisplayName("兩個貨主應共用同一個 SKU 代碼，且指向完全不同的商品——這是撞號情境的最小再現")
  void seedsTheCrossOwnerSkuCodeCollision() {
    List<Map<String, Object>> collidingSkus = jdbcTemplate.queryForList("""
        SELECT o.code AS owner_code, s.spec_name, s.weight_gram, p.name AS product_name
        FROM skus s
        JOIN owners o ON o.id = s.owner_id
        JOIN products p ON p.owner_id = s.owner_id AND p.product_code = s.product_code
        WHERE s.sku_code = ?
        ORDER BY o.code
        """, DevSeedDataInitializer.AVAILABLE_SKU);

    assertThat(collidingSkus).hasSize(2);
    assertThat(collidingSkus).extracting(row -> row.get("owner_code"))
        .containsExactly("OWNER-A", "OWNER-B");
    // 同一個代碼指的是兩件不同的商品，連重量都不同——R6 的成本函數會因此得出不同結果
    assertThat(collidingSkus).extracting(row -> row.get("product_name"))
        .containsExactly("烏龍茶", "麥茶");
    assertThat(collidingSkus).extracting(row -> row.get("weight_gram"))
        .containsExactly(520, 610);
  }

  @Test
  @DisplayName("兩貨主的倉庫指派應重疊但不相等，且有一個倉同時服務兩個貨主")
  void seedsOverlappingButUnequalWarehouseAssignments() {
    List<UUID> first = nodeIdsOf(DevSeedDataInitializer.FIRST_OWNER_ID);
    List<UUID> second = nodeIdsOf(DevSeedDataInitializer.SECOND_OWNER_ID);

    // 同一貨主有多個倉
    assertThat(first).hasSize(2);
    assertThat(second).hasSize(2);
    // 不同貨主的倉不同
    assertThat(first).isNotEqualTo(second);
    // 一個倉服務多個貨主——3PL 的定義性特徵。少了這條，一個「以倉庫而非指派關係做過濾」
    // 的錯誤實作會安靜通過，因為每個倉只屬於一個貨主時兩種寫法結果相同。
    assertThat(first).containsAnyElementsOf(second);
  }

  private List<UUID> nodeIdsOf(UUID ownerId) {
    return fulfillmentNodeRepository.findByOwner(ownerId).stream()
        .map(FulfillmentNode::getId)
        .toList();
  }

  @Test
  @DisplayName("應同時有常溫與冷凍，且其中一款帶兩個重量不同的規格")
  void seedsBothTemperatureZonesAndATwoSpecificationProduct() {
    assertThat(jdbcTemplate.queryForList(
        "SELECT DISTINCT temperature_zone FROM products", String.class))
        .containsExactlyInAnyOrder("AMBIENT", "FROZEN");

    // 款／規格兩層要在畫面上看得出來，就得有一款真的帶兩個規格
    assertThat(jdbcTemplate.queryForList("""
        SELECT weight_gram FROM skus
        WHERE owner_id = ? AND product_code = ?
        ORDER BY sku_code
        """, Integer.class,
        DevSeedDataInitializer.FIRST_OWNER_ID,
        DevSeedDataInitializer.AMBIENT_PRODUCT_CODE))
        .containsExactly(520, 1000);
  }

  /**
   * 這張單目前還有效的預留。
   *
   * <p>{@code stock_reservations} 指向 {@code order_lines}，所以要先從訂單取行的 id——與
   * {@code ReleaseReservationUsecase} 走同一條路。回的是清單而不是單筆：一條行跨三批就有
   * 三筆預留。
   */
  private java.util.List<com.flowzati.archone.allocation.domain.model.StockReservation>
      activeReservationsOf(java.util.UUID orderId) {
    return orderRepository.findById(orderId)
        .map(order -> stockReservationRepository.findActiveByOrderLineIds(
            order.getLines().stream().map(line -> line.getId()).toList()))
        .orElse(java.util.List.of());
  }

  /** 依 id 取那一批。種子的日期相對於今天計算，所以用 id 取比用五維鍵拼出來可靠。 */
  private StockPool batch(java.util.UUID stockPoolId) {
    return stockPoolRepository.findById(stockPoolId).orElseThrow();
  }
}
