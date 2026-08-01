package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.common.IdGenerator;
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

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher dispatcher;

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
    jdbcTemplate.execute("DELETE FROM stock_locations");
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
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(1);
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));

    allocateOrderUsecase.handle(inbound(orderId, eventId));

    assertThat(inboxRepository.findById(eventId)).isPresent();
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
        .isEqualTo(1);
    assertThat(outboxRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("配置業務失敗時應回滾 Inbox claim 與 Order 狀態")
  void shouldRollBackInboxClaimWhenBusinessHandlingFails() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    // 以**資料庫的 unique constraint** 逼出失敗：uq_stock_reservations_line_pool 不允許同一條
    // 行對同一批有第二筆預留。先塞一筆 RELEASED 的，配貨要建新預留時就會撞上。
    //
    // RELEASED 是關鍵：demand_lines 的「已滿足」謂詞是 ACTIVE／CONSUMED，所以這條行仍然出現
    // 在待配需求裡，配貨會走完整條路徑直到寫入才失敗——那正是要驗回滾的位置。
    //
    // **失敗來源換過兩次了。** 最早是「查無庫存池」，分批之後那變成缺貨（正常結果，不拋錯）；
    // 接著改用「下單時間在未來」讓 markAllocated 拒絕，而配貨現在根本不呼叫那個方法——訂單
    // 狀態由 ordering 收到事件後自己推進。兩次都不是壞在測試身上，是它依賴的檢查搬走了。
    // 資料庫約束不會這樣消失：它與配貨的實作無關，只要預留還是「一條行對一批一筆」就成立。
    Order pending = OrderFixtures.pendingOrder(orderId, "SKU-1", 3, Instant.now().minusSeconds(1));
    orderRepository.save(pending);
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));
    stockReservationRepository.save(StockReservation.rehydrate(
        IdGenerator.nextId(), orderId, pending.getLines().getFirst().getId(), stockPoolId, 3,
        ReservationStatus.RELEASED, Instant.now().minusSeconds(2), Instant.now().minusSeconds(1),
        null));

    assertThatThrownBy(() -> allocateOrderUsecase.handle(inbound(orderId, eventId)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // 失敗發生在 inbox claim 之後，所以那筆 claim 必須跟著回滾——否則重送會被當成重複而丟棄，
    // 那張單就永遠停在 PENDING。
    assertThat(inboxRepository.findById(eventId)).isEmpty();
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    // 只剩那筆預先塞的 RELEASED——配貨要建的那筆隨交易一起回滾了。
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stock_reservations WHERE status = 'ACTIVE'", Integer.class)).isZero();
  }

  @Test
  @DisplayName("釋放 Reservation 失敗時應回滾 Inbox 與庫存狀態")
  void shouldRollBackInboxClaimWhenReservationReleaseFails() {
    UUID orderId = IdGenerator.nextId();
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
        reservationId,
        orderId, order.getLines().get(0).getId(), stockPoolId, 3, reservedAt));

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
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    // 失敗來源同上一支：預留的 unique constraint，而不是任何配貨層的檢查。理由見那裡。
    Instant receivedAt = Instant.now().minusSeconds(60);
    Order queued = OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt);
    orderRepository.save(queued);
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 0, 0));
    stockReservationRepository.save(StockReservation.rehydrate(
        IdGenerator.nextId(), orderId, queued.getLines().getFirst().getId(), stockPoolId, 3,
        ReservationStatus.RELEASED, receivedAt, receivedAt.plusSeconds(1), null));

    assertThatThrownBy(() -> replenishmentUsecase.handle(new InboundCommand<>(
        new ReplenishStockCommand(
            com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID,
            com.flowzati.archone.testsupport.OrderFixtures.NODE_ID,
            com.flowzati.archone.testsupport.OrderFixtures.LOCATION_ID, "SKU-1",
            StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 3),
        new MessageMetadata(eventId, "StockReplenishedIntegrationEvent"))))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isZero();
      assertThat(pool.getReservedQuantity()).isZero();
    });
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
    // 只數 ACTIVE：預先塞的那筆 RELEASED 是失敗注入的裝置，本來就該留著。配貨要建的那筆
    // 隨交易一起回滾了。
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stock_reservations WHERE status = 'ACTIVE'", Integer.class))
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
    return stockReservationRepository.findActiveByOrderId(orderId);
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
