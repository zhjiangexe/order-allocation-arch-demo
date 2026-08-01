package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.event.InventoryEventTopics;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 釘住 FIFO 保證的<strong>範圍</strong>：它只涵蓋「補貨事件處理當下的佇列快照」，不是全域
 * 時間序。補貨喚醒之後剩下的 ATP 會被下一張路過的新單直接取得，而佇列裡等更久的大單繼續
 * 等——這是刻意的政策，不是缺陷。
 *
 * <p><b>這支測試存在的理由是它描述的行為看起來像 bug。</b>在操作台上它會表現為「明明有貨，
 * 舊單卻沒配到」，而最直覺的反應是把它「修掉」——讓佇列非空時新單也排隊。那個修法會直接
 * 損失可履約訂單，並與 {@code MaximizeFulfilledOrdersPolicy} 的存在理由矛盾。政策的取捨與
 * 兩個備選方案見 {@code docs/dom-promising-scope.md} 的「補貨的三個決定」。
 *
 * <p>數字刻意設計成讓代價無法被忽略：舊單要 100，兩次補貨合計正好 100。<b>若沒有新單插隊，
 * 舊單第二次補貨後剛好配得到</b>；新單取走的那 10 個單位，正是舊單最後差的那 10 個。斷言
 * 因此不只證明「新單配到了」，也證明「舊單為此被推遲」。
 *
 * <p>與 {@code AllocationFifoReplenishmentBatchIntegrationTest} 的分工：那支測的是佇列
 * <em>內部</em>的順序（嚴格 FIFO 與 head-of-line blocking），這支測的是佇列<em>外部</em>
 * 的邊界（誰有資格繞過佇列）。
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationFifoGuaranteeScopeIntegrationTest {

  private static final String SKU = "SCOPE-SKU";
  private static final int QUEUED_ORDER_QUANTITY = 100;
  private static final int FIRST_REPLENISH_QUANTITY = 30;
  private static final int NEW_ORDER_QUANTITY = 10;
  private static final int SECOND_REPLENISH_QUANTITY = 70;

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
    jdbcTemplate.execute("DELETE FROM event_outbox");
    jdbcTemplate.execute("DELETE FROM event_inbox");
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM order_lines");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
    jdbcTemplate.execute("DELETE FROM skus");
    jdbcTemplate.execute("DELETE FROM products");
    jdbcTemplate.execute("DELETE FROM owner_nodes");
    jdbcTemplate.execute("DELETE FROM owners");
    jdbcTemplate.execute("DELETE FROM stock_locations");
    jdbcTemplate.execute("DELETE FROM fulfillment_nodes");
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔，寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, SKU);
  }

  @Test
  @DisplayName("補貨後的餘量會被後到的新單取得，佇列裡等更久的大單因此被推遲——FIFO 只保證補貨當下的佇列快照")
  void shouldLetANewOrderTakeLeftoverAtpAheadOfAnOlderQueuedOrder() throws Exception {
    // Step 1：一個空的庫存池，與一張已經排隊很久、需求 100 的缺貨訂單。
    UUID stockPoolId = UUID.randomUUID();
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, SKU, 0, 0));
    UUID queuedOrderId = seedQueuedOrder();

    // Step 2：補進 30。佇列的 head 要 100，head-of-line blocking 讓它配不到，
    // 這 30 個單位原封不動留在池裡。
    consumer.consumeInventoryEvent(record(
        InventoryEventTopics.STOCK_EVENTS, replenishment(FIRST_REPLENISH_QUANTITY)));

    assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(availableToPromise(stockPoolId)).isEqualTo(FIRST_REPLENISH_QUANTITY);

    // Step 3：此時一張全新的訂單到達，需求 10。它走 AllocateOrderUsecase 的 fast path，
    // 不查佇列——這一步就是本測試釘住的契約。
    UUID newOrderId = placeNewOrder();

    // Step 4：新單配到，舊單仍在佇列裡。餘量從 30 降為 20。
    assertThat(statusOf(newOrderId)).isEqualTo(OrderStatus.ALLOCATED);
    assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(availableToPromise(stockPoolId))
        .isEqualTo(FIRST_REPLENISH_QUANTITY - NEW_ORDER_QUANTITY);

    // Step 5：再補 70，兩次補貨合計正好 100——恰好是舊單的需求量。
    consumer.consumeInventoryEvent(record(
        InventoryEventTopics.STOCK_EVENTS, replenishment(SECOND_REPLENISH_QUANTITY)));

    // Step 6：舊單仍配不到。這就是插隊的代價：進來的貨總量足夠，但其中 10 個已經給了新單，
    // 剩下的 90 湊不滿它的 100。若把這條斷言改綠（例如讓佇列非空時新單也排隊），
    // 改的就不是一個 bug，而是本系統的配貨政策。
    assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(availableToPromise(stockPoolId))
        .isEqualTo(FIRST_REPLENISH_QUANTITY + SECOND_REPLENISH_QUANTITY - NEW_ORDER_QUANTITY);
  }

  private UUID seedQueuedOrder() {
    UUID orderId = IdGenerator.nextId();
    Instant backorderedAt = Instant.now().minusSeconds(3600);
    orderRepository.save(OrderFixtures.backorderedOrder(
        orderId, SKU, QUEUED_ORDER_QUANTITY, backorderedAt.minusSeconds(1), backorderedAt));
    return orderId;
  }

  private UUID placeNewOrder() throws Exception {
    UUID orderId = IdGenerator.nextId();
    Instant receivedAt = Instant.now();
    orderRepository.save(
        OrderFixtures.pendingOrder(orderId, SKU, NEW_ORDER_QUANTITY, receivedAt));
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
    consumer.consumeOrderingEvent(
        record(OrderingEventTopics.ORDER_EVENTS, event));
    return orderId;
  }

  private StockReplenishedIntegrationEvent replenishment(int quantity) {
    return new StockReplenishedIntegrationEvent(
            UUID.randomUUID(), OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.NODE_ID, SKU,
            StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, quantity);
  }

  private OrderStatus statusOf(UUID orderId) {
    // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
    // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
    outcomeDrain().drain();

    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private int availableToPromise(UUID stockPoolId) {
    return stockPoolRepository.findById(stockPoolId).orElseThrow().availableToPromise();
  }

  private ConsumerRecord<String, String> record(String topic, IntegrationEvent event)
      throws Exception {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        topic, 0, 0, SKU, objectMapper.writeValueAsString(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers()
        .add("eventType", event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
    return new com.flowzati.archone.testsupport.AllocationOutcomeDrain(jdbcTemplate, dispatcher);
  }
}
