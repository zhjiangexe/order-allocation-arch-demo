package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.SitDatabase;
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
  private JpaEventInboxRepository inboxRepository;

  @Autowired
  private JpaOutboxRepository outboxRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearDatabase() {
    SitDatabase.clear(jdbcTemplate);
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
    // 一張作業單、一段已鎖定的搬運、一條明細——三者要在同一次 commit 裡一起出現。
    assertThat(count("stock_pickings")).isEqualTo(1);
    assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
    assertThat(MovementFixtures.heldBy(jdbcTemplate, orderId)).hasSize(1);
    assertThat(outboxRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("配貨業務失敗時應回滾 Inbox claim，且不留下半張作業單")
  void shouldRollBackInboxClaimWhenBusinessHandlingFails() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    orderRepository.save(
        OrderFixtures.pendingOrder(orderId, "SKU-1", 3, Instant.now().minusSeconds(1)));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));

    // **失敗來源換過三次了，而這一次的理由與前兩次不同。**
    //
    // 最早是「查無庫存池」，分批之後那變成缺貨（正常結果，不拋錯）；接著改用「下單時間在未來」
    // 讓 markAllocated 拒絕，而配貨已經不呼叫那個方法；再來改用預留的 unique constraint。
    // 前兩次都是「它依賴的檢查搬走了」。
    //
    // 這一次不是搬走，是**沒有了**：收單即建搬運之後，配貨這個交易寫的全是**當場產生 id 的
    // 新列**（作業單、搬運、明細），資料庫裡沒有任何既有的列能與它衝突。因此改用一個業務
    // 前提：倉必須有出庫作業類型。它由 spec 保證，不會隨配貨的實作改變。
    jdbcTemplate.update(
        "DELETE FROM stock_picking_types WHERE warehouse_id = ?", OrderFixtures.NODE_ID);

    assertThatThrownBy(() -> allocateOrderUsecase.handle(inbound(orderId, eventId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no outbound operation type");

    // 失敗發生在 inbox claim 之後，所以那筆 claim 必須跟著回滾——否則重送會被當成重複而丟棄，
    // 那張單就永遠停在 PENDING 且沒有任何搬運。
    assertThat(inboxRepository.findById(eventId)).isEmpty();
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(count("stock_pickings")).isZero();
    assertThat(count("stock_moves")).isZero();
  }

  @Test
  @DisplayName("釋放失敗時應回滾 Inbox 與庫存，鎖住的量原封不動")
  void shouldRollBackInboxClaimWhenReleaseFails() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant allocatedAt = Instant.now().minusSeconds(1);
    Order order = OrderFixtures.allocatedOrder(
        orderId, "SKU-1", 3, allocatedAt.minusSeconds(1), allocatedAt);
    orderRepository.save(order);
    // 批只鎖了 2 件，明細卻說鎖了 3 件——釋放時 StockPool 會拒絕，因為那會讓預留量變成負的。
    //
    // 這是刻意造出來的不一致：正常路徑產不出它（配貨同時寫兩邊）。但要驗的是**交易邊界**，
    // 失敗注入本來就得從外面塞。
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 2));
    MovementFixtures.seedAssignedPicking(jdbcTemplate, order, stockPoolId, 3);

    assertThatThrownBy(() -> releaseReservationUsecase.handle(new InboundCommand<>(
        new ReleaseReservationCommand(orderId),
        new MessageMetadata(eventId, "OrderCancelledIntegrationEvent"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isEqualTo(2));
    // 搬運與明細都必須原封不動——一段已取消的搬運配著沒被刪的明細，是最難查的一種狀態。
    assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
    assertThat(MovementFixtures.heldBy(jdbcTemplate, orderId)).singleElement()
        .satisfies(held -> assertThat(held.quantity()).isEqualTo(3));
  }

  @Test
  @DisplayName("補貨配置失敗時應回滾 Inbox、庫存與搬運")
  void shouldRollBackInboxClaimWhenReplenishmentAllocationFails() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(60);
    Order queued = MovementFixtures.saveQueuedOrder(orderRepository, jdbcTemplate,
        OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 0, 0));

    // 失敗來源：`uq_stock_move_lines_move_pool`。先替這張單那段還在等貨的搬運塞一條指向同一
    // 批的明細，補貨喚醒配到貨、要寫明細時就會撞上。
    //
    // **這條路在上一支測試已經走不通了**（那裡的搬運是當場建的，id 事先不存在）；補貨這裡
    // 還在，正是因為搬運早在收單時就建好了。
    jdbcTemplate.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        SELECT ?, m.id, ?, 3
          FROM stock_moves m
          JOIN stock_pickings p ON p.id = m.picking_id
         WHERE p.order_id = ?
        """, IdGenerator.nextId(), stockPoolId, queued.getId());

    assertThatThrownBy(() -> replenishmentUsecase.handle(new InboundCommand<>(
        new ReplenishStockCommand(
            OrderFixtures.OWNER_ID,
            OrderFixtures.NODE_ID,
            OrderFixtures.LOCATION_ID, "SKU-1",
            StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, 3),
        new MessageMetadata(eventId, "StockReplenishedIntegrationEvent"))))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    assertThat(inboxRepository.findById(eventId)).isEmpty();
    // 補進去的 3 件也要回滾——庫存的加法與喚醒在同一個交易裡，那正是 FIFO 的實作機制。
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isZero();
      assertThat(pool.getReservedQuantity()).isZero();
    });
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED));
    // 搬運仍在等貨——它沒有被那次失敗的喚醒轉成已鎖定。
    assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CONFIRMED");
  }

  private InboundCommand<AllocateOrderCommand> inbound(UUID orderId, UUID eventId) {
    return new InboundCommand<>(
        new AllocateOrderCommand(orderId),
        new MessageMetadata(eventId, "OrderPlacedIntegrationEvent"));
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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
