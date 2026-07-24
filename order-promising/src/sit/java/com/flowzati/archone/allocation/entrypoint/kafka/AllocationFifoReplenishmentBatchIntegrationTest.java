package com.flowzati.archone.allocation.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationFifoReplenishmentBatchIntegrationTest {

  private static final String FIFO_SKU = "FIFO-SKU";
  private static final int FITTING_ORDERS_BEFORE_BLOCKER = 500;
  private static final int FITTING_ORDERS_AFTER_BLOCKER = 499;
  private static final int TOTAL_ORDERS =
      FITTING_ORDERS_BEFORE_BLOCKER + 1 + FITTING_ORDERS_AFTER_BLOCKER;
  private static final int BLOCKER_QUANTITY = 999;
  private static final int FIRST_REPLENISH_QUANTITY = FITTING_ORDERS_BEFORE_BLOCKER;
  private static final int SECOND_REPLENISH_QUANTITY = BLOCKER_QUANTITY + FITTING_ORDERS_AFTER_BLOCKER;

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
    jdbcTemplate.execute("DELETE FROM event_outbox");
    jdbcTemplate.execute("DELETE FROM event_inbox");
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
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
    stockPoolRepository.save(new StockPool(stockPoolId, FIFO_SKU, 0, 0, null));
    BackorderQueue queue = seedBackorderQueue();

    // Step 2：第一次補貨，數量精準等於前 500 張的總和，逼出「blocker 之後全部停止」的
    // 批次決策，而不是靠隨機數量碰運氣。
    consume(new StockReplenishedIntegrationEvent(UUID.randomUUID(), FIFO_SKU, FIRST_REPLENISH_QUANTITY));

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
    consume(new StockReplenishedIntegrationEvent(UUID.randomUUID(), FIFO_SKU, SECOND_REPLENISH_QUANTITY));

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
    UUID orderId = UUID.randomUUID();
    Instant backorderedAt = firstBackorderedAt.plusMillis(fifoPosition);
    Order order = Order.rehydrate(
        orderId,
        FIFO_SKU,
        quantity,
        OrderStatus.BACKORDERED,
        backorderedAt.minusSeconds(1),
        null,
        backorderedAt,
        null,
        null);
    orderRepository.save(order);
    return orderId;
  }

  private OrderStatus statusOf(UUID orderId) {
    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private void assertReconciledState(UUID stockPoolId, ExpectedState expected) {
    // 1) Order 結果：ALLOCATED／BACKORDERED 的張數要精準對上這個階段的預期。
    Integer allocatedCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM orders WHERE sku = ? AND status = 'ALLOCATED'", Integer.class, FIFO_SKU);
    Integer backorderedCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM orders WHERE sku = ? AND status = 'BACKORDERED'", Integer.class, FIFO_SKU);
    assertThat(allocatedCount).isEqualTo(expected.allocated());
    assertThat(backorderedCount).isEqualTo(expected.backordered());

    // 2) Reservation 結果：ACTIVE 筆數、總量都要精確等於這個階段累積補貨量，且 order_id 不重複。
    Integer activeReservationCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM stock_reservations WHERE status = 'ACTIVE'", Integer.class);
    Integer activeReservationQuantity = jdbcTemplate.queryForObject(
        "SELECT coalesce(sum(quantity), 0) FROM stock_reservations WHERE status = 'ACTIVE'", Integer.class);
    Integer distinctReservedOrders = jdbcTemplate.queryForObject(
        "SELECT count(DISTINCT order_id) FROM stock_reservations WHERE status = 'ACTIVE'", Integer.class);
    assertThat(activeReservationCount).isEqualTo(expected.reservationCount());
    assertThat(activeReservationQuantity).isEqualTo(expected.reservationQuantity());
    assertThat(distinctReservedOrders).isEqualTo(expected.reservationCount());

    // 3) StockPool 結果：on-hand 等於累積補貨量、reserved 全部用完、ATP 歸零。
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(expected.onHand());
      assertThat(pool.getReservedQuantity()).isEqualTo(expected.reserved());
      assertThat(pool.availableToPromise()).isZero();
    });

    // 4) Inbox 結果：累積送出的補貨事件數要對上 claim 筆數，代表沒有事件被重複處理或遺失。
    Integer inboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM event_inbox", Integer.class);
    assertThat(inboxCount).isEqualTo(expected.inboxCount());

    // 5) Outbox 結果：只有被配置的訂單各發一筆 OrderAllocatedIntegrationEvent，這個測試
    //    情境全程不會發布 BackorderCreatedIntegrationEvent（訂單一開始就是直接種成
    //    BACKORDERED，沒有經過真正的下單配置流程）。
    Integer outboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM event_outbox", Integer.class);
    Integer allocatedOutboxCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_outbox WHERE type = ?", Integer.class,
        OrderAllocatedIntegrationEvent.class.getSimpleName());
    assertThat(outboxCount).isEqualTo(expected.outboxAllocatedCount());
    assertThat(allocatedOutboxCount).isEqualTo(expected.outboxAllocatedCount());
  }

  private void consume(StockReplenishedIntegrationEvent event) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC,
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
}
