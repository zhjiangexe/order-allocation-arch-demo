package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.stock.application.event.BackorderWakeRequestedIntegrationEvent;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.SitDatabase;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo-02：1,000 張已排隊的同 SKU 缺貨訂單，被循序到達的 StockReplenished 事件喚醒後的
 * 嚴格 FIFO 批次配置決策。
 *
 * <p>驗證範圍：循序（非併發）補貨事件觸發的批次配置演算法，在有意義的排隊量體下依然嚴格
 * 遵守 FIFO 與 head-of-line blocking——第一波補貨不足時正確整批停止在第一張補不滿的訂單，
 * 且不跳過去配置後面數量更小、原本配得起的訂單；第二波補貨到位後，先前被卡住的訂單也能
 * 正確恢復配置。不涉及多個補貨事件同時到達的併發競爭，也不是 production
 * throughput/latency benchmark。
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = {
        "spring.kafka.listener.auto-startup=false",
        // 上限寫在測試裡而不是吃 production 預設：預設值會隨壓測結果調整，那不該讓這支
        // 測試變色。它驗的是「分多輪會收斂」，與上限的具體數字無關。
        "archone.allocation.replenishment-wake-limit=" + AllocationFifoReplenishmentBatchIntegrationTest.WAKE_LIMIT_TEXT
    },
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationFifoReplenishmentBatchIntegrationTest {

  static final String WAKE_LIMIT_TEXT = "200";
  private static final int WAKE_LIMIT = Integer.parseInt(WAKE_LIMIT_TEXT);
  /**
   * 續做的硬上限。超過就讓測試失敗——那代表終止條件失效、續做無限循環。
   *
   * <p>取「佇列長度 ÷ 上限」再留兩輪餘裕：真正要守的性質是**有界**，不是某個精確的輪數，
   * 而精確的輪數會隨演算法的細節變動，釘死它只會讓測試變脆。
   */
  private static final int MAX_CONTINUATION_ROUNDS = 1_000 / WAKE_LIMIT + 2;

  private static final String FIFO_SKU = "FIFO-SKU";
  private static final int FITTING_ORDERS_BEFORE_BLOCKER = 500;
  private static final int FITTING_ORDERS_AFTER_BLOCKER = 499;
  private static final int TOTAL_ORDERS =
      FITTING_ORDERS_BEFORE_BLOCKER + 1 + FITTING_ORDERS_AFTER_BLOCKER;
  private static final int BLOCKER_QUANTITY = 999;
  private static final int FIRST_REPLENISH_QUANTITY = FITTING_ORDERS_BEFORE_BLOCKER;
  private static final int SECOND_REPLENISH_QUANTITY = BLOCKER_QUANTITY + FITTING_ORDERS_AFTER_BLOCKER;

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher dispatcher;

  @Autowired
  private AllocationKafkaIntegrationEventConsumer consumer;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearDatabase() {
    SitDatabase.clear(jdbcTemplate);
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "FIFO-SKU");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("循序補貨事件喚醒 1,000 張排隊缺貨訂單時應嚴格 FIFO 批次配置，且不足時正確卡住、補足後正確恢復")
  void shouldAllocateStrictFifoQueueAcrossSequentialReplenishments() {
    // Step 1：種入一個目前完全沒有庫存的 StockPool，以及 1,000 張已經在排隊的 BACKORDERED
    // Order。FIFO 排序靠 backorderedSince 逐筆遞增保證穩定，跟 production 的
    // `ORDER BY backordered_since ASC, id ASC` 對齊。額外記住三個關鍵位置的 orderId
    // （最早、blocker、最晚），之後用來做「不是只看聚合數字」的精準身分驗證——
    // 因為除了 blocker 之外每張訂單 quantity 都是 1，光看總數／總量無法分辨
    // FIFO 有沒有選對「哪幾張」，只能證明選對「幾張」。
    UUID stockPoolId = UUID.randomUUID();
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, FIFO_SKU, 0, 0));
    BackorderQueue queue = seedBackorderQueue();

    // Step 2：第一次補貨，數量精準等於前 500 張的總和，逼出「blocker 之後全部停止」的
    // 批次決策，而不是靠隨機數量碰運氣。
    //
    // **一次補貨事件不再喚醒整個佇列。** 喚醒有張數上限，超出時發一則續做事件；因此這裡要
    // 等的不是「一個事件處理完」，而是「續做收斂」。SIT 沒有 Debezium，續做事件停在 outbox，
    // 所以由 drainContinuations() 手動把它們餵回 consumer——那正是 production 裡 Kafka 會做
    // 的事，只是在這裡是同步的、可數的。
    consume(replenish(FIRST_REPLENISH_QUANTITY));
    int firstPhaseRounds = drainContinuations();

    // 分多輪是這個 change 的重點之一：如果只跑了一輪，代表上限沒有生效，而後面那些
    // 「收斂後的狀態」斷言就退化成了舊行為的斷言。
    assertThat(firstPhaseRounds)
        .withFailMessage("第一波補貨應分多輪續做，實際只有 %d 輪", firstPhaseRounds)
        .isGreaterThan(0);

    // Step 3：對帳第一階段——前 500 張應該已配置，blocker 與其後 499 張仍應卡在 BACKORDERED。
    assertReconciledState(stockPoolId, new ExpectedState(
        /* allocated */ FITTING_ORDERS_BEFORE_BLOCKER,
        /* backordered */ TOTAL_ORDERS - FITTING_ORDERS_BEFORE_BLOCKER,
        /* reservationCount */ FITTING_ORDERS_BEFORE_BLOCKER,
        /* reservationQuantity */ FIRST_REPLENISH_QUANTITY,
        /* onHand */ FIRST_REPLENISH_QUANTITY,
        /* reserved */ FIRST_REPLENISH_QUANTITY,
        /* inboxCount */ 1,
        /* outboxAllocatedCount */ FITTING_ORDERS_BEFORE_BLOCKER));
    assertThat(statusOf(queue.firstOrderId())).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(statusOf(queue.blockerOrderId())).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(statusOf(queue.lastOrderId())).isEqualTo(OrderStatus.BACKORDERED);

    // Step 4：第二次（循序、非併發）補貨，數量等於 blocker 與其後 499 張的總和，
    // 驗證「喚醒佇列」的後半段——先前被 head-of-line blocking 卡住的訂單，補貨到位後
    // 應該能正確恢復配置，而不只是第一波卡住就結束驗證。
    consume(replenish(SECOND_REPLENISH_QUANTITY));
    drainContinuations();

    // Step 5：對帳第二階段——整個佇列應該全部配置完畢，沒有訂單被遺漏或重複配置。
    int totalReplenished = FIRST_REPLENISH_QUANTITY + SECOND_REPLENISH_QUANTITY;
    assertReconciledState(stockPoolId, new ExpectedState(
        /* allocated */ TOTAL_ORDERS,
        /* backordered */ 0,
        /* reservationCount */ TOTAL_ORDERS,
        /* reservationQuantity */ totalReplenished,
        /* onHand */ totalReplenished,
        /* reserved */ totalReplenished,
        /* inboxCount */ 2,
        /* outboxAllocatedCount */ TOTAL_ORDERS));
    assertThat(statusOf(queue.firstOrderId())).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(statusOf(queue.blockerOrderId())).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(statusOf(queue.lastOrderId())).isEqualTo(OrderStatus.ALLOCATED);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("blocker 卡在隊首且庫存還有量時不得續做——否則每輪讀滿上限卻配不到任何一張，永不停止")
  void stopsInsteadOfLoopingWhenTheHeadOfLineIsBlockedWithStockRemaining() {
    // 這個情境是「讀到幾張」與「配到幾張」唯一會分歧的地方，也是唯一能分辨終止條件寫對沒有
    // 的地方：
    //
    //   讀到 = 上限（每輪都讀滿），配到 = 0（FIFO 停在隊首那張配不滿的單）
    //
    // 以讀取數當判準就會無限續做——而且庫存沒耗盡，allocatableBatches 不會變空，沒有任何
    // 別的機制會讓它停下來。上一支測試碰不到這件事，因為那裡庫存剛好用完。
    UUID stockPoolId = UUID.randomUUID();
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, FIFO_SKU, 0, 0));
    Instant backorderedAt = Instant.now().minusSeconds(3600);
    int position = 0;
    UUID blockerOrderId = seedBackorderedOrder(BLOCKER_QUANTITY, backorderedAt, position++);
    for (int i = 0; i < WAKE_LIMIT * 2; i++) {
      seedBackorderedOrder(1, backorderedAt, position++);
    }

    // 補的量餵不飽 blocker，但遠遠足夠餵飽它後面那些單——所以「庫存還有」與「配不到」同時成立。
    consume(replenish(BLOCKER_QUANTITY - 1));
    int rounds = drainContinuations();

    assertThat(rounds)
        .withFailMessage("blocker 卡住時不該續做，實際續做了 %d 輪", rounds)
        .isZero();
    assertThat(statusOf(blockerOrderId)).isEqualTo(OrderStatus.BACKORDERED);
    // 庫存一件都沒被動用：head-of-line blocking 不是「跳過去配小單」。
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isZero());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("別的倉的更早訂單不得進入本輪——它們這次補貨滿足不了，卻會佔滿以張數計的上限")
  void excludesOrdersShippingFromAnotherWarehouse() {
    UUID stockPoolId = UUID.randomUUID();
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, FIFO_SKU, 0, 0));

    // **先種別的倉的單**，所以它們的 order_id 較小、在佇列裡排更前面。少了倉別篩選，它們
    // 會排在最前面被讀進來，然後因為查不到自己那個倉的批次而一張張被跳過——不會出錯，
    // 但整個上限就這樣被用光，真正配得到的單一張都輪不到。
    List<UUID> otherWarehouseOrders = new java.util.ArrayList<>();
    for (int i = 0; i < WAKE_LIMIT; i++) {
      UUID orderId = IdGenerator.nextId();
      MovementFixtures.saveQueuedOrder(orderRepository, jdbcTemplate, OrderFixtures.backorderedOrderAt(
          OrderFixtures.OTHER_NODE_ID, orderId, OrderFixtures.OWNER_ID, FIFO_SKU, 1,
          Instant.now().minusSeconds(7200), Instant.now().minusSeconds(7200)));
      otherWarehouseOrders.add(orderId);
    }
    UUID mine = seedBackorderedOrder(1, Instant.now().minusSeconds(60), 0);

    consume(replenish(1));
    drainContinuations();

    // 補的是本倉的一件，該配到的是本倉那張——即使它在佇列裡排在最後面。
    assertThat(statusOf(mine)).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(otherWarehouseOrders)
        .withFailMessage("別的倉的訂單不該被這次補貨碰到")
        .allSatisfy(id -> assertThat(statusOf(id)).isEqualTo(OrderStatus.BACKORDERED));
  }

  /** 建立 FIFO 排序穩定的 1,000 張 BACKORDERED Order：前 500 張、blocker、後 499 張。 */
  private BackorderQueue seedBackorderQueue() {
    Instant firstBackorderedAt = Instant.now().minusSeconds(3600);
    int position = 0;
    UUID firstOrderId = seedBackorderedOrder(1, firstBackorderedAt, position++);
    for (int i = 1; i < FITTING_ORDERS_BEFORE_BLOCKER; i++) {
      seedBackorderedOrder(1, firstBackorderedAt, position++);
    }
    UUID blockerOrderId = seedBackorderedOrder(BLOCKER_QUANTITY, firstBackorderedAt, position++);
    UUID lastOrderId = null;
    for (int i = 0; i < FITTING_ORDERS_AFTER_BLOCKER; i++) {
      lastOrderId = seedBackorderedOrder(1, firstBackorderedAt, position++);
    }
    return new BackorderQueue(firstOrderId, blockerOrderId, lastOrderId);
  }

  private UUID seedBackorderedOrder(int quantity, Instant firstBackorderedAt, int fifoPosition) {
    UUID orderId = IdGenerator.nextId();
    Instant backorderedAt = firstBackorderedAt.plusMillis(fifoPosition);
    Order order = OrderFixtures.backorderedOrder(
        orderId, FIFO_SKU, quantity, backorderedAt.minusSeconds(1), backorderedAt);
    // 訂單與它的作業單、還在等貨的搬運要一起寫——佇列讀的是搬運，只存訂單造出來的是一張
    // 任何佇列都看不見的單。
    MovementFixtures.saveQueuedOrder(orderRepository, jdbcTemplate, order);
    return orderId;
  }

  private OrderStatus statusOf(UUID orderId) {
    // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
    // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
    outcomeDrain().drain();

    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private void assertReconciledState(UUID stockPoolId, ExpectedState expected) {
    // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
    // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
    outcomeDrain().drain();

    // 1) Order 結果：ALLOCATED／BACKORDERED 的張數要精準對上這個階段的預期。
    Integer allocatedCount = jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'ALLOCATED'
        """, Integer.class, FIFO_SKU);
    Integer backorderedCount = jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'BACKORDERED'
        """, Integer.class, FIFO_SKU);
    assertThat(allocatedCount).isEqualTo(expected.allocated());
    assertThat(backorderedCount).isEqualTo(expected.backordered());

    // 2) 鎖定結果：明細筆數、總量都要精確等於這個階段累積補貨量，且 order_id 不重複。
    // 「還有效」不再是一個狀態欄位——**明細存在就代表鎖著**，釋放是刪除那一列。因此三個
    // 查詢都不帶條件；少了那個 WHERE 正是這次遷移在這裡的全部內容。
    //
    // 區域變數仍叫 reservation：命名收斂集中在第四個 change，這裡動它會讓「斷言一字未改」
    // 這件事變得難以核對。
    // **只數為需求鎖住的那些明細。** 入庫走搬運之後，stock_move_lines 同時是收貨的紀錄——
    // 不篩的話補進來的每一批都會被算成一筆預留。判準是「這條明細背後有訂單行」，那正是
    // 「為某張單鎖的」的定義；收貨的搬運沒有訂單行。
    Integer activeReservationCount = jdbcTemplate.queryForObject("""
        SELECT count(*)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
         WHERE m.order_line_id IS NOT NULL
        """, Integer.class);
    Integer activeReservationQuantity = jdbcTemplate.queryForObject("""
        SELECT coalesce(sum(ml.quantity), 0)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
         WHERE m.order_line_id IS NOT NULL
        """, Integer.class);
    Integer distinctReservedOrders = jdbcTemplate.queryForObject("""
        SELECT count(DISTINCT m.order_line_id)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
        """, Integer.class);
    assertThat(activeReservationCount).isEqualTo(expected.reservationCount());
    assertThat(activeReservationQuantity).isEqualTo(expected.reservationQuantity());
    assertThat(distinctReservedOrders).isEqualTo(expected.reservationCount());

    // 3) StockPool 結果：on-hand 等於累積補貨量、reserved 全部用完、ATP 歸零。
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(expected.onHand());
      assertThat(pool.getReservedQuantity()).isEqualTo(expected.reserved());
      assertThat(pool.availableToPromise()).isZero();
    });

    // 4) Inbox 結果：每一則送進來的事件（補貨 + 續做）恰好一筆 claim。續做事件的數量由
    //    佇列長度決定，所以這裡驗的是「至少有那幾則補貨事件」與「沒有任何事件被處理兩次」
    //    ——後者靠 inbox 的主鍵保證，這裡把它斷言出來。
    Integer inboxCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_inbox", Integer.class);
    Integer distinctInboxCount = jdbcTemplate.queryForObject(
        "SELECT count(DISTINCT event_id) FROM event_inbox", Integer.class);
    assertThat(inboxCount).isGreaterThanOrEqualTo(expected.inboxCount());
    assertThat(distinctInboxCount).isEqualTo(inboxCount);

    // 5) Outbox 結果：只有被配置的訂單各發一筆 OrderAllocatedIntegrationEvent，這個測試
    //    情境全程不會發布 BackorderCreatedIntegrationEvent（訂單一開始就是直接種成
    //    BACKORDERED，沒有經過真正的下單配置流程）。分批之後 outbox 還會有續做事件，
    //    所以只斷言配置結果那一類的筆數，不斷言 outbox 總筆數。
    Integer allocatedOutboxCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_outbox WHERE type = ?", Integer.class,
        OrderAllocatedIntegrationEvent.class.getSimpleName());
    assertThat(allocatedOutboxCount).isEqualTo(expected.outboxAllocatedCount());
  }

  private StockReplenishedIntegrationEvent replenish(int quantity) {
    return new StockReplenishedIntegrationEvent(
        UUID.randomUUID(), OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, FIFO_SKU,
        StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, quantity);
  }

  /**
   * 把 outbox 裡尚未處理的續做事件餵回 consumer，直到不再產生新的，回傳輪數。
   *
   * <p>production 裡這件事由 Debezium 與 Kafka 完成；SIT 沒有它們，續做事件會停在 outbox。
   * 手動驅動的好處是**輪數變成可數的**，於是「會收斂」與「不會無限續做」都能斷言。
   *
   * <p>硬上限是這支測試最重要的一條:終止條件若失效（例如改成以「還有沒有配到的單」判斷），
   * 一張永遠配不到的大單會讓續做無限循環，而沒有上限的話這支測試會掛到 @Timeout 才失敗，
   * 訊息還不會說明原因。
   */
  private int drainContinuations() {
    Set<UUID> processed = new HashSet<>();
    int rounds = 0;
    while (true) {
      List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
          SELECT id, payload FROM event_outbox WHERE type = ? ORDER BY timestamp
          """, BackorderWakeRequestedIntegrationEvent.class.getSimpleName());
      List<Map<String, Object>> fresh = pending.stream()
          .filter(row -> !processed.contains(UUID.fromString(row.get("id").toString())))
          .toList();
      if (fresh.isEmpty()) {
        return rounds;
      }
      assertThat(++rounds)
          .withFailMessage("續做超過 %d 輪仍未收斂——終止條件失效了", MAX_CONTINUATION_ROUNDS)
          .isLessThanOrEqualTo(MAX_CONTINUATION_ROUNDS);
      for (Map<String, Object> row : fresh) {
        UUID eventId = UUID.fromString(row.get("id").toString());
        processed.add(eventId);
        consumeWake(eventId, row.get("payload").toString());
      }
    }
  }

  private void consumeWake(UUID eventId, String payload) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        InventoryEventTopics.STOCK_EVENTS, 0, 0, FIFO_SKU, payload);
    record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType",
        BackorderWakeRequestedIntegrationEvent.class.getSimpleName()
            .getBytes(StandardCharsets.UTF_8));
    consumer.consumeInventoryEvent(record);
  }

  private void consume(StockReplenishedIntegrationEvent event) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        InventoryEventTopics.STOCK_EVENTS,
        0,
        0,
        event.getSku(),
        serialize(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", StockReplenishedIntegrationEvent.class.getSimpleName()
        .getBytes(StandardCharsets.UTF_8));
    consumer.consumeInventoryEvent(record);
  }

  private String serialize(IntegrationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize test integration event", exception);
    }
  }

  /** FIFO 佇列中三個關鍵位置的 orderId，用來做不依賴聚合數字的精準身分驗證。 */
  private record BackorderQueue(UUID firstOrderId, UUID blockerOrderId, UUID lastOrderId) {
  }

  /** 某一階段補貨後，預期的持久化狀態快照。 */
  private record ExpectedState(
      int allocated,
      int backordered,
      int reservationCount,
      int reservationQuantity,
      int onHand,
      int reserved,
      int inboxCount,
      int outboxAllocatedCount) {
  }

  private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
    return new com.flowzati.archone.testsupport.AllocationOutcomeDrain(jdbcTemplate, dispatcher);
  }
}
