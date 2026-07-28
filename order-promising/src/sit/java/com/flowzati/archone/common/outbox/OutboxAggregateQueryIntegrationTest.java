package com.flowzati.archone.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.entrypoint.kafka.AllocationKafkaIntegrationEventConsumer;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.testsupport.OrderFixtures;
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
        "archone.allocation.partition-key-strategy=sku"
    },
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class OutboxAggregateQueryIntegrationTest {

  private static final String SKU = "SKU-CHAIN";

  @Autowired
  private PlaceOrderUsecase placeOrderUsecase;

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
    jdbcTemplate.execute("DELETE FROM owners");
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-CHAIN");
  }

  @Test
  @DisplayName("sku 分區策略下，仍能以 orderId 查回該訂單完整的事件因果鏈")
  void shouldReturnFullEventChainByOrderIdUnderSkuPartitionStrategy() throws Exception {
    stockPoolRepository.save(new StockPool(UUID.randomUUID(), SKU, 0, 0, null));

    UUID orderId = placeOrder();
    backorderIt(orderId);
    replenishStock();

    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));

    assertThat(eventTypesFor(orderId)).containsExactly(
        OrderPlacedIntegrationEvent.class.getSimpleName(),
        BackorderCreatedIntegrationEvent.class.getSimpleName(),
        OrderAllocatedIntegrationEvent.class.getSimpleName());
  }

  @Test
  @DisplayName("sku 分區策略下，下單事件的 partition key 是 SKU，配置結果事件是 orderId")
  void shouldKeepDeliveryKeysSeparateFromAggregateIdentity() throws Exception {
    stockPoolRepository.save(new StockPool(UUID.randomUUID(), SKU, 0, 0, null));

    UUID orderId = placeOrder();
    backorderIt(orderId);
    replenishStock();

    assertThat(partitionKeysFor(orderId)).containsExactly(SKU, orderId.toString(), orderId.toString());
  }

  private UUID placeOrder() {
    Order placed = placeOrderUsecase.placeOrder(new PlaceOrderCommand(
        OrderFixtures.OWNER_ID,
        "EXT-" + UUID.randomUUID(),
        "100",
        "台北市中正區重慶南路一段 122 號",
        java.time.LocalDate.of(2026, 8, 1),
        null,
        java.util.List.of(new PlaceOrderCommand.Line(SKU, 3))));
    // 下單當下 StockPool 的 ATP 是 0，配置決策要等這筆下單事件被 allocation 消費才發生。
    assertThat(placed.getStatus()).isEqualTo(OrderStatus.PENDING);
    return placed.getId();
  }

  private void backorderIt(UUID orderId) throws Exception {
    Order order = orderRepository.findById(orderId).orElseThrow();
    consumer.consumeOrderingEvent(record(
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        new OrderPlacedIntegrationEvent(
            UUID.randomUUID(), orderId, SKU, order.getDemandFor(SKU), order.getPlacedAt())));
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(backordered ->
        assertThat(backordered.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
  }

  private void replenishStock() throws Exception {
    consumer.consumeInventoryEvent(record(
        IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC,
        new StockReplenishedIntegrationEvent(UUID.randomUUID(), com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, SKU, 3)));
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
        topic, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
    return record;
  }
}
