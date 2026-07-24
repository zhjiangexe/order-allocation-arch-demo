package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class InboundCommandTransactionIntegrationTest {

  @Autowired
  private AllocateOrderUsecase allocateOrderUsecase;

  @Autowired
  private ReleaseReservationUsecase releaseReservationUsecase;

  @Autowired
  private ReplenishmentUsecase replenishmentUsecase;

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
  @DisplayName("成功處理訊息時應在同一交易提交 Inbox、業務資料與 Outbox")
  void shouldCommitInboxAndBusinessUpdatesInOneTransaction() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant placedAt = Instant.now().minusSeconds(1);
    orderRepository.save(Order.place(orderId, "SKU-1", 3, placedAt));
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-1", 10, 0, null));

    allocateOrderUsecase.handle(inbound(orderId, eventId));

    assertThat(inboxRepository.findById(eventId)).isPresent();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
        .isEqualTo(1);
    assertThat(outboxRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("配置業務失敗時應回滾 Inbox claim 與 Order 狀態")
  void shouldRollBackInboxClaimWhenBusinessHandlingFails() {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    orderRepository.save(Order.place(orderId, "MISSING-SKU", 3, Instant.parse("2026-07-24T10:00:00Z")));

    assertThatThrownBy(() -> allocateOrderUsecase.handle(inbound(orderId, eventId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StockPool not found for SKU: MISSING-SKU");

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
  }

  @Test
  @DisplayName("釋放 Reservation 失敗時應回滾 Inbox 與庫存狀態")
  void shouldRollBackInboxClaimWhenReservationReleaseFails() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant reservedAt = Instant.now().plusSeconds(60);
    orderRepository.save(Order.rehydrate(
        orderId, "SKU-1", 3, OrderStatus.ALLOCATED, reservedAt.minusSeconds(1), reservedAt,
        null, null, null));
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-1", 3, 3, null));
    stockReservationRepository.save(
        StockReservation.create(reservationId, orderId, stockPoolId, 3, reservedAt));

    assertThatThrownBy(() -> releaseReservationUsecase.handle(new InboundCommand<>(
        new ReleaseReservationCommand(orderId),
        new MessageMetadata(eventId, "OrderCancelledIntegrationEvent"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time cannot be before reserved time");

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isEqualTo(3));
    assertThat(stockReservationRepository.findActiveByOrderId(orderId)).hasValueSatisfying(reservation ->
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
  }

  @Test
  @DisplayName("補貨配置失敗時應回滾 Inbox、庫存、訂單與 Reservation")
  void shouldRollBackInboxClaimWhenReplenishmentAllocationFails() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant placedAt = Instant.now().plusSeconds(60);
    orderRepository.save(Order.rehydrate(
        orderId, "SKU-1", 3, OrderStatus.BACKORDERED, placedAt, null, placedAt, null, null));
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-1", 0, 0, null));

    assertThatThrownBy(() -> replenishmentUsecase.handle(new InboundCommand<>(
        new ReplenishStockCommand("SKU-1", 3),
        new MessageMetadata(eventId, "StockReplenishedIntegrationEvent"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Allocated time cannot be before placed time");

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isZero();
      assertThat(pool.getReservedQuantity()).isZero();
    });
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
        .isZero();
  }

  private InboundCommand<AllocateOrderCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(
        new AllocateOrderCommand(orderId),
        new MessageMetadata(eventId, "OrderPlacedIntegrationEvent"));
  }
}
