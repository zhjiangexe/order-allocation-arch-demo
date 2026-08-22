package com.flowzati.archone.inventory.balance.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.balance.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptRequestConflictException;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class InboundEntrypointTransactionIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.InventoryEventDrainFactory inventoryEventDrainFactory;

    @Autowired
    private AllocationOrderLifecycleEventDriver allocationConsumer;

    @Autowired
    private StockReceiptApplicationFacade stockReceiptApplicationFacade;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StockQuantRepository stockQuantRepository;

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
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));

        consumeOrderingEvent(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId);

        assertThat(inboxClaimExists(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isTrue();
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        // 一張作業單、一段已鎖定的搬運、一條明細——三者要在同一次 commit 裡一起出現。
        assertThat(count("stock_pickings")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_pickings WHERE order_id = ?", String.class, orderId))
                .isEqualTo("ASSIGNED");
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
        assertThat(MovementFixtures.heldBy(jdbcTemplate, orderId)).hasSize(1);
        assertThat(count("event_outbox")).isOne();
    }

    @Test
    @DisplayName("配貨業務失敗時應回滾 Inbox claim，且不留下半張作業單")
    void shouldRollBackInboxClaimWhenBusinessHandlingFails() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));

        // **失敗來源換過三次了，而這一次的理由與前兩次不同。**
        //
        // 最早是「查無庫存池」，分批之後那變成缺貨（正常結果，不拋錯）；接著改用「下單時間在未來」
        // 讓 markAllocated 拒絕，而配貨已經不呼叫那個方法；再來改用預留的 unique constraint。
        // 前兩次都是「它依賴的檢查搬走了」。
        //
        // 這一次不是搬走，是**沒有了**：收單即建搬運之後，配貨這個交易寫的全是**當場產生 id 的
        // 新列**（作業單、搬運、明細），資料庫裡沒有任何既有的列能與它衝突。因此改用一個業務
        // 前提：倉必須有出庫作業類型。它由 spec 保證，不會隨配貨的實作改變。
        jdbcTemplate.update("DELETE FROM stock_picking_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);

        assertThatThrownBy(() ->
                        consumeOrderingEvent(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no outbound operation type");

        // 失敗發生在 inbox claim 之後，所以那筆 claim 必須跟著回滾——否則重送會被當成重複而丟棄，
        // 那張單就永遠停在 PENDING 且沒有任何搬運。
        assertThat(inboxClaimExists(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isFalse();
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(count("stock_pickings")).isZero();
        assertThat(count("stock_moves")).isZero();
    }

    @Test
    @DisplayName("釋放失敗時應回滾 Inbox 與庫存，鎖住的量原封不動")
    void shouldRollBackInboxClaimWhenReleaseFails() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant allocatedAt = Instant.now().minusSeconds(1);
        Order order = OrderFixtures.allocatedOrder(orderId, "SKU-1", 3, allocatedAt.minusSeconds(1), allocatedAt);
        orderRepository.save(order);
        // 批只鎖了 2 件，明細卻說鎖了 3 件——釋放時 StockQuant 會拒絕，因為那會讓預留量變成負的。
        //
        // 這是刻意造出來的不一致：正常路徑產不出它（配貨同時寫兩邊）。但要驗的是**交易邊界**，
        // 失敗注入本來就得從外面塞。
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 2));
        MovementFixtures.seedAssignedPicking(jdbcTemplate, order, stockQuantId, 3);

        assertThatThrownBy(() -> consumeOrderingEvent(
                        new OrderCancelledIntegrationEvent(eventId, orderId, Instant.now()), orderId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quantity to release cannot exceed reserved quantity");

        assertThat(inboxClaimExists(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isFalse();
        assertThat(stockQuantRepository.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(2));
        // 搬運與明細都必須原封不動——一段已取消的搬運配著沒被刪的明細，是最難查的一種狀態。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
        assertThat(MovementFixtures.heldBy(jdbcTemplate, orderId))
                .singleElement()
                .satisfies(held -> assertThat(held.quantity()).isEqualTo(3));
    }

    @Test
    @DisplayName("availability 事件併發重送仍應只提交一次配置")
    void shouldCommitReceiptBeforeTheAvailabilityTriggeredAllocation() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(60);
        MovementFixtures.saveQueuedOrder(
                orderRepository,
                jdbcTemplate,
                OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        StockReceiptRequest request = receiptRequest(eventId, 3);
        stockReceiptApplicationFacade.confirm(request);
        stockReceiptApplicationFacade.confirm(request);

        assertThat(countReceiptRequests(eventId)).isEqualTo(1);
        assertThat(stockQuantRepository.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE order_line_id IS NULL AND state = 'DONE'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);

        UUID availabilityEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_outbox WHERE type = ?",
                UUID.class,
                StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE);
        com.flowzati.archone.testsupport.InventoryEventDrain inventoryEvents = inventoryDrain();
        deliverConcurrently(inventoryEvents, availabilityEventId);
        inventoryEvents.redeliver(availabilityEventId);
        assertThat(inventoryEvents.drain()).isZero();

        assertThat(inboxClaimExists(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, availabilityEventId))
                .isTrue();
        assertThat(stockQuantRepository.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(3));
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE type = ?",
                        Integer.class,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE))
                .isOne();
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
    }

    @Test
    @DisplayName("收貨提交後配貨失敗，不得回滾已完成的 inbound execution 與庫存")
    void shouldKeepTheCommittedReceiptWhenLaterAllocationFails() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(60);
        Order queued = MovementFixtures.saveQueuedOrder(
                orderRepository,
                jdbcTemplate,
                OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        // 失敗來源：`uq_stock_move_lines_move_pool`。先替這張單那段還在等貨的搬運塞一條指向同一
        // 批的明細，可用量增加後喚醒配到貨、要寫明細時就會撞上。
        //
        // 這裡撞的是早在收單時就建好的 outbound move，不是 availability 路徑新建的搬運。
        jdbcTemplate.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        SELECT ?, m.id, ?, 3
          FROM stock_moves m
          JOIN stock_pickings p ON p.id = m.picking_id
         WHERE p.order_id = ?
        """, IdGenerator.nextId(), stockQuantId, queued.getId());

        stockReceiptApplicationFacade.confirm(receiptRequest(eventId, 3));

        UUID availabilityEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_outbox WHERE type = ?",
                UUID.class,
                StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE);

        assertThatThrownBy(() -> inventoryDrain().drain())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(inboxClaimExists(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, availabilityEventId))
                .isFalse();
        assertThat(countReceiptRequests(eventId)).isEqualTo(1);
        // 收貨是已完成的獨立 checkpoint；後續 outbound 配貨失敗不能撤銷實際到貨。
        assertThat(stockQuantRepository.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        // 搬運仍在等貨——它沒有被那次失敗的喚醒轉成已鎖定。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CONFIRMED");
        // inbound execution 與 availability Outbox 保留，讓事件重試或 scheduler 日後收斂。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE order_line_id IS NULL AND state = 'DONE'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 receiptId 綁定不同收貨內容時拒絕，不得再異動庫存")
    void shouldRejectAReceiptIdReusedForDifferentContent() {
        UUID stockQuantId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 3));

        assertThatThrownBy(() -> stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 4)))
                .isInstanceOf(StockReceiptRequestConflictException.class)
                .hasMessageContaining(receiptId.toString());

        assertThat(countReceiptRequests(receiptId)).isEqualTo(1);
        assertThat(stockQuantRepository.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE order_line_id IS NULL AND state = 'DONE'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("收貨業務失敗時 request claim、庫存與 Outbox 必須一起回滾")
    void shouldRollBackTheReceiptClaimWhenBusinessHandlingFails() {
        UUID receiptId = UUID.randomUUID();
        jdbcTemplate.update("DELETE FROM stock_picking_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);

        assertThatThrownBy(() -> stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no inbound operation type");

        assertThat(countReceiptRequests(receiptId)).isZero();
        assertThat(count("stock_pickings")).isZero();
        assertThat(count("stock_moves")).isZero();
        assertThat(count("stock_move_lines")).isZero();
        assertThat(count("event_outbox")).isZero();
    }

    private void consumeOrderingEvent(OrderPlacedIntegrationEvent event, UUID orderId) {
        if (!event.getOrderId().equals(orderId)) {
            throw new IllegalArgumentException("Order event does not match the requested order");
        }
        allocationConsumer.consume(event);
    }

    private void consumeOrderingEvent(OrderCancelledIntegrationEvent event, UUID orderId) {
        if (!event.getOrderId().equals(orderId)) {
            throw new IllegalArgumentException("Order event does not match the requested order");
        }
        allocationConsumer.consume(event);
    }

    private boolean inboxClaimExists(String subscriberId, UUID eventId) {
        return jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM event_inbox
         WHERE subscriber_id = ? AND event_id = ?
        """, Integer.class, subscriberId, eventId) == 1;
    }

    private StockReceiptRequest receiptRequest(UUID receiptId, int quantity) {
        return new StockReceiptRequest(
                receiptId,
                new ConfirmStockReceiptCommand(
                        OrderFixtures.OWNER_ID,
                        OrderFixtures.FACILITY_ID,
                        OrderFixtures.LOCATION_ID,
                        "SKU-1",
                        StockFixtures.ARRIVED_ON,
                        StockFixtures.EXPIRES_ON,
                        quantity));
    }

    private int countReceiptRequests(UUID receiptId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_receipt_requests WHERE receipt_id = ?", Integer.class, receiptId);
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
        return outcomeDrainFactory.create();
    }

    private com.flowzati.archone.testsupport.InventoryEventDrain inventoryDrain() {
        return inventoryEventDrainFactory.create();
    }

    private void deliverConcurrently(com.flowzati.archone.testsupport.InventoryEventDrain inventoryEvents, UUID eventId)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> inventoryEvents.redeliver(eventId));
            Future<?> second = executor.submit(() -> inventoryEvents.redeliver(eventId));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }
    }
}
