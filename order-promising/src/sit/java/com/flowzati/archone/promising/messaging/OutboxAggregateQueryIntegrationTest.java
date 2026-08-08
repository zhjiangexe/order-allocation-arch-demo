package com.flowzati.archone.promising.messaging;

import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.stock.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.stock.entrypoint.kafka.AllocationKafkaIntegrationEventConsumer;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.SitDatabase;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 驗證 outbox row 的領域身分與傳輸決策確實分離：不論分區策略為何，Order aggregate 的
 * 事件一律以 orderId 作為 {@code aggregateid}，SKU 只出現在 {@code partition_key}。
 *
 * <p>以「用 orderId 查得到這張訂單發布過的全部事件」作為斷言手法，是因為那是這個性質
 * 最直接的可觀察後果——把 partition key 塞在 {@code aggregateid} 的舊實作在 sku 策略下
 * 會漏掉下單事件。這裡不預設任何查詢端存在，純粹是對 schema 誠實度的迴歸測試。
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = {
        "spring.kafka.listener.auto-startup=false",
        "archone.allocation.partition-key-strategy=stock"
    },
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class OutboxAggregateQueryIntegrationTest {

  private static final String SKU = "SKU-CHAIN";

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher dispatcher;

  @Autowired
  private PlaceOrderUsecase placeOrderUsecase;

  @Autowired
  private ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

  @Autowired
  private AllocationKafkaIntegrationEventConsumer consumer;

  @Autowired
  private IntegrationEventSerializer eventSerializer;

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
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-CHAIN");
  }

  @Test
  @DisplayName("stock 分區策略下，仍能以 orderId 查回該訂單完整的事件因果鏈")
  void shouldReturnFullEventChainByOrderIdUnderSkuPartitionStrategy() throws Exception {
    stockPoolRepository.save(StockFixtures.unexpiredBatch(SKU, 0, 0));

    UUID orderId = placeOrder();
    backorderIt(orderId);
    confirmStockReceipt();

    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));

    assertThat(eventTypesFor(orderId)).containsExactly(
        OrderPlacedIntegrationEvent.EVENT_TYPE,
        BackorderCreatedIntegrationEvent.EVENT_TYPE,
        OrderAllocatedIntegrationEvent.EVENT_TYPE);
  }

  @Test
  @DisplayName("stock 分區策略下，下單事件的 partition key 是 (貨主, 倉)，配置結果事件是 orderId")
  void shouldKeepDeliveryKeysSeparateFromAggregateIdentity() throws Exception {
    stockPoolRepository.save(StockFixtures.unexpiredBatch(SKU, 0, 0));

    UUID orderId = placeOrder();
    backorderIt(orderId);
    confirmStockReceipt();

    // 下單事件的 key 是爭用群組（貨主/倉/SKU），配貨結果事件維持 orderId。
    String contentionKey = com.flowzati.archone.promising.messaging.StockContentionKey.of(
        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID);
    assertThat(partitionKeysFor(orderId))
        .containsExactly(contentionKey, orderId.toString(), orderId.toString());
  }

  private UUID placeOrder() {
    Order placed = placeOrderUsecase.placeOrder(new PlaceOrderCommand(
        OrderFixtures.OWNER_ID,
        "EXT-" + UUID.randomUUID(),
        "100",
        "台北市中正區重慶南路一段 122 號",
        java.time.LocalDate.of(2026, 8, 1),
        OrderFixtures.FACILITY_ID,
        null,
        java.util.List.of(new PlaceOrderCommand.Line(SKU, 3))));
    // 下單當下 StockPool 的 ATP 是 0，配置決策要等這筆下單事件被 allocation 消費才發生。
    assertThat(placed.getStatus()).isEqualTo(OrderStatus.PENDING);
    return placed.getId();
  }

  private void backorderIt(UUID orderId) throws Exception {
    Order order = orderRepository.findById(orderId).orElseThrow();
    consumer.consumeOrderingEvent(record(
        OrderingEventTopics.ORDER_EVENTS,
        new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, order.getReceivedAt())));
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(backordered ->
        assertThat(backordered.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
  }

  private void confirmStockReceipt() {
    com.flowzati.archone.testsupport.StockReceiptFixture.confirm(
        confirmStockReceiptUsecase, SKU, 3);
    new com.flowzati.archone.testsupport.InventoryEventDrain(jdbcTemplate, dispatcher).drain();
  }

  private List<String> eventTypesFor(UUID orderId) {
    return jdbcTemplate.queryForList(
        "SELECT type FROM event_outbox WHERE aggregatetype = ? AND aggregateid = ? ORDER BY timestamp",
        String.class, OutboxAggregateTypes.ORDER, orderId.toString());
  }

  private List<String> partitionKeysFor(UUID orderId) {
    return jdbcTemplate.queryForList(
        "SELECT partition_key FROM event_outbox WHERE aggregatetype = ? AND aggregateid = ? ORDER BY timestamp",
        String.class, OutboxAggregateTypes.ORDER, orderId.toString());
  }

  private ConsumerRecord<String, String> record(String topic, IntegrationEvent event) throws Exception {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        topic, 0, 0, "key", eventSerializer.serialize(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
    return record;
  }

  /**
   * 把 outbox 的配貨結果餵回 ordering。
   *
   * <p>配貨只寫自己的表並發事件，訂單狀態由 ordering 收到後推進；SIT 沒有 Debezium，那一段
   * 得自己走完——production 裡是 Kafka 做這件事。
   */
  private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
    return new com.flowzati.archone.testsupport.AllocationOutcomeDrain(jdbcTemplate, dispatcher);
  }
}
