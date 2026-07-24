package com.flowzati.archone.allocation.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.integration.IntegrationEvent;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationWorkflowEndToEndIntegrationTest {

  @Autowired
  private AllocationKafkaIntegrationEventConsumer consumer;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private StockReservationRepository stockReservationRepository;

  @Autowired
  private JpaEventInboxRepository inboxRepository;

  @Autowired
  private JpaOutboxRepository outboxRepository;

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
  @DisplayName("下單整合事件應完成配置、寫入 Inbox 並建立 Outbox")
  void shouldAllocateOrderFromKafkaIntegrationEventAndWriteOutbox() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    Instant placedAt = Instant.now().minusSeconds(1);
    orderRepository.save(Order.place(orderId, "SKU-AVAILABLE", 3, placedAt));
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-AVAILABLE", 10, 0, null));

    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), orderId, "SKU-AVAILABLE", 3, placedAt);
    consumer.consumeOrderingEvent(record(IntegrationEventTopics.ORDERING_ORDER_EVENTS, event));

    assertThat(inboxRepository.findById(event.getEventId())).isPresent();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isEqualTo(3));
    assertThat(stockReservationRepository.findActiveByOrderId(orderId)).hasValueSatisfying(reservation -> {
      assertThat(reservation.getStockPoolId()).isEqualTo(stockPoolId);
      assertThat(reservation.getQuantity()).isEqualTo(3);
    });
    assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
      assertThat(outbox.getEventType()).isEqualTo(OrderAllocatedIntegrationEvent.class.getSimpleName());
      assertThat(outbox.getRoute()).isEqualTo(IntegrationEventTopics.PROMISING_ALLOCATION_EVENTS);
      assertThat(outbox.getAggregateId()).isEqualTo(orderId.toString());
    });
  }

  @Test
  @DisplayName("取消整合事件應釋放有效 Reservation 與 ATP")
  void shouldReleaseActiveReservationFromKafkaCancellationEvent() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    Instant reservedAt = Instant.now().minusSeconds(1);
    orderRepository.save(Order.rehydrate(
        orderId, "SKU-PARTIALLY-RESERVED", 4, OrderStatus.ALLOCATED, reservedAt.minusSeconds(1),
        reservedAt, null, null, null));
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-PARTIALLY-RESERVED", 10, 4, null));
    stockReservationRepository.save(
        StockReservation.create(reservationId, orderId, stockPoolId, 4, reservedAt));

    OrderCancelledIntegrationEvent event = new OrderCancelledIntegrationEvent(
        UUID.randomUUID(), orderId, Instant.now());
    consumer.consumeOrderingEvent(record(IntegrationEventTopics.ORDERING_ORDER_EVENTS, event));

    assertThat(inboxRepository.findById(event.getEventId())).isPresent();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isZero());
    assertThat(stockReservationRepository.findActiveByOrderId(orderId)).isEmpty();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM stock_reservations WHERE id = ?", String.class, reservationId))
        .isEqualTo("RELEASED");
    assertThat(outboxRepository.count()).isZero();
  }

  @Test
  @DisplayName("補貨整合事件應只按嚴格 FIFO 配置可完整滿足的前段訂單")
  void shouldAllocateOnlyFifoPrefixWhenReplenishingFromKafkaIntegrationEvent() throws Exception {
    UUID stockPoolId = UUID.randomUUID();
    UUID firstOrderId = UUID.randomUUID();
    UUID secondOrderId = UUID.randomUUID();
    Instant firstBackorderedAt = Instant.now().minusSeconds(4);
    Instant secondBackorderedAt = firstBackorderedAt.plusSeconds(1);
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-FIFO", 0, 0, null));
    orderRepository.save(backorderedOrder(firstOrderId, "SKU-FIFO", 3, firstBackorderedAt));
    orderRepository.save(backorderedOrder(secondOrderId, "SKU-FIFO", 3, secondBackorderedAt));

    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(
        UUID.randomUUID(), "SKU-FIFO", 5);
    consumer.consumeInventoryEvent(record(IntegrationEventTopics.INVENTORY_STOCK_EVENTS, event));

    assertThat(inboxRepository.findById(event.getEventId())).isPresent();
    assertThat(orderRepository.findById(firstOrderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(orderRepository.findById(secondOrderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(5);
      assertThat(pool.getReservedQuantity()).isEqualTo(3);
    });
    assertThat(stockReservationRepository.findActiveByOrderId(firstOrderId)).isPresent();
    assertThat(stockReservationRepository.findActiveByOrderId(secondOrderId)).isEmpty();
    assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox ->
        assertThat(outbox.getEventType()).isEqualTo(OrderAllocatedIntegrationEvent.class.getSimpleName()));
  }

  private Order backorderedOrder(UUID orderId, String sku, int quantity, Instant backorderedAt) {
    return Order.rehydrate(
        orderId,
        sku,
        quantity,
        OrderStatus.BACKORDERED,
        backorderedAt.minusSeconds(1),
        null,
        backorderedAt,
        null,
        null);
  }

  private ConsumerRecord<String, String> record(String topic, IntegrationEvent event) throws Exception {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        topic, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
    return record;
  }
}
