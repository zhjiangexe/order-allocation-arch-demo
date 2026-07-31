package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.domain.model.StockFixtures;
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
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.UUID;
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
    jdbcTemplate.execute("DELETE FROM order_lines");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
    jdbcTemplate.execute("DELETE FROM skus");
    jdbcTemplate.execute("DELETE FROM products");
    jdbcTemplate.execute("DELETE FROM owner_nodes");
    jdbcTemplate.execute("DELETE FROM owners");
    jdbcTemplate.execute("DELETE FROM fulfillment_nodes");
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1", "MISSING-SKU");
  }

  @Test
  @DisplayName("成功處理訊息時應在同一交易提交 Inbox、業務資料與 Outbox")
  void shouldCommitInboxAndBusinessUpdatesInOneTransaction() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(1);
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));

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
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    // 以「下單時間在未來」逼出領域層的失敗：markAllocated 拒絕早於 receivedAt 的配貨時間。
    //
    // **不能再用「查無庫存」當失敗來源**——分批之後那是缺貨，是正常結果（掛帳），不再丟例外。
    // 拿它當失敗情境的話，這支測試會靜默地什麼都沒測到：不拋錯，assertThatThrownBy 直接失敗。
    orderRepository.save(
        OrderFixtures.pendingOrder(orderId, "SKU-1", 3, Instant.now().plusSeconds(3600)));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));

    assertThatThrownBy(() -> allocateOrderUsecase.handle(inbound(orderId, eventId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Allocated time cannot be before placed time");

    // 失敗發生在 inbox claim 之後，所以那筆 claim 必須跟著回滾——否則重送會被當成重複而丟棄，
    // 那張單就永遠停在 PENDING。
    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stock_reservations", Integer.class)).isZero();
  }

  @Test
  @DisplayName("釋放 Reservation 失敗時應回滾 Inbox 與庫存狀態")
  void shouldRollBackInboxClaimWhenReservationReleaseFails() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant reservedAt = Instant.now().plusSeconds(60);
    Order order = OrderFixtures.allocatedOrder(
        orderId, "SKU-1", 3, reservedAt.minusSeconds(1), reservedAt);
    orderRepository.save(order);
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 3, 3));
    // 預留指向**行**而不是訂單：外鍵是 fk_stock_reservations_order_line。
    stockReservationRepository.save(StockReservation.create(
        reservationId, order.getLines().get(0).getId(), stockPoolId, 3, reservedAt));

    assertThatThrownBy(() -> releaseReservationUsecase.handle(new InboundCommand<>(
        new ReleaseReservationCommand(orderId),
        new MessageMetadata(eventId, "OrderCancelledIntegrationEvent"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Released time cannot be before reserved time");

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isEqualTo(3));
    assertThat(activeReservationsOf(orderId)).singleElement().satisfies(reservation ->
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
  }

  @Test
  @DisplayName("補貨配置失敗時應回滾 Inbox、庫存、訂單與 Reservation")
  void shouldRollBackInboxClaimWhenReplenishmentAllocationFails() {
    UUID orderId = UUID.randomUUID();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().plusSeconds(60);
    orderRepository.save(OrderFixtures.backorderedOrder(
        orderId, "SKU-1", 3, receivedAt, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 0, 0));

    assertThatThrownBy(() -> replenishmentUsecase.handle(new InboundCommand<>(
        new ReplenishStockCommand(
            com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.NODE_ID, "SKU-1",
            StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 3),
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

  /**
   * 這張單目前還有效的預留。
   *
   * <p>{@code stock_reservations} 指向 {@code order_lines}，所以要先從訂單取行的 id——與
   * {@code ReleaseReservationUsecase} 走同一條路。
   */
  private java.util.List<com.flowzati.archone.allocation.domain.model.StockReservation>
      activeReservationsOf(java.util.UUID orderId) {
    return orderRepository.findById(orderId)
        .map(order -> stockReservationRepository.findActiveByOrderLineIds(
            order.getLines().stream().map(line -> line.getId()).toList()))
        .orElse(java.util.List.of());
  }
}
