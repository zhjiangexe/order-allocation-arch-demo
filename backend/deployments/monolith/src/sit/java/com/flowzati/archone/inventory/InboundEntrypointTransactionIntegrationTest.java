package com.flowzati.archone.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.application.port.WarehouseCancellationDecision;
import com.flowzati.archone.inventory.movement.application.port.WarehouseExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.position.application.StockReceiptRequest;
import com.flowzati.archone.inventory.position.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.position.application.exception.StockReceiptRequestConflictException;
import com.flowzati.archone.inventory.position.application.service.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.reservation.entrypoint.ReservationAssignmentEventSubscriptions;
import com.flowzati.archone.inventory.reservation.entrypoint.ReservationIntakeEventSubscriptions;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
    private StockQuantStore stockQuantStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WarehouseExecutionCancellationCoordinator warehouseCancellationCoordinator;

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
    @BeforeEach
    void seedCatalogForOrders() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1", "MISSING-SKU");
        when(warehouseCancellationCoordinator.cancelExecution(any(), any()))
                .thenReturn(WarehouseCancellationDecision.CONFIRMED);
    }

    @Test
    @DisplayName("成功處理訊息時應在同一交易提交 Inbox、業務資料與 Outbox")
    void shouldCommitInboxAndBusinessUpdatesInOneTransaction() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));

        consumeOrderingEvent(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId);

        assertThat(inboxClaimExists(ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isTrue();
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        // Canonical operation、move 與 reservation 必須在 assignment transaction 後一致。
        assertThat(count("stock_operations")).isOne();
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
        assertThat(MovementFixtures.heldBy(jdbcTemplate, orderId)).hasSize(1);
        assertThat(count("event_outbox")).isOne();
    }

    @Test
    @DisplayName("配貨業務失敗時應回滾 Inbox claim，且不留下半成品 target")
    void shouldRollBackInboxClaimWhenBusinessHandlingFails() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));

        // **失敗來源換過三次了，而這一次的理由與前兩次不同。**
        //
        // 最早是「查無庫存池」，分批之後那變成缺貨（正常結果，不拋錯）；接著改用「下單時間在未來」
        // 讓 markAllocated 拒絕，而配貨已經不呼叫那個方法；再來改用預留的 unique constraint。
        // 前兩次都是「它依賴的檢查搬走了」。
        //
        // Source adapter 以 outbound operation type 解出 immutable source/destination；缺少它是穩定的
        // acceptance failure，且發生在 Inbox claim 後，適合驗證整個 subscriber transaction rollback。
        jdbcTemplate.update("DELETE FROM stock_operation_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);

        assertThatThrownBy(() ->
                        consumeOrderingEvent(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no outbound operation type");

        // 失敗發生在 inbox claim 之後，所以那筆 claim 必須跟著回滾——否則重送會被當成重複而丟棄，
        // 那張單就永遠停在 PENDING 且沒有任何搬運。
        assertThat(inboxClaimExists(ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isFalse();
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(count("stock_operations")).isZero();
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
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));
        MovementFixtures.seedAssignedPicking(jdbcTemplate, order, stockQuantId, 3);

        // 以合法且一致的 assigned group 起始，再拒絕 quant 的 release update，驗證整個取消交易回滾。
        jdbcTemplate.execute(
                "ALTER TABLE stock_pools ADD CONSTRAINT ck_test_reject_release CHECK (reserved_quantity >= 3) NOT VALID");
        try {
            assertThatThrownBy(() -> consumeOrderingEvent(
                            new OrderCancelledIntegrationEvent(eventId, orderId, Instant.now()), orderId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE stock_pools DROP CONSTRAINT ck_test_reject_release");
        }

        assertThat(inboxClaimExists(ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, eventId))
                .isFalse();
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(3));
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
        MovementFixtures.saveConfirmedPickingOrder(
                orderRepository,
                jdbcTemplate,
                OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        StockReceiptRequest request = receiptRequest(eventId, 3);
        stockReceiptApplicationFacade.confirm(request);
        stockReceiptApplicationFacade.confirm(request);

        assertThat(countReceiptRequests(eventId)).isEqualTo(1);
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        // Receipt commit 尚未消費 availability event；outbound movement intent 已存在但仍未保留庫存。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE source_line_id IS NULL AND state = 'DONE'",
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

        assertThat(inboxClaimExists(
                        ReservationAssignmentEventSubscriptions.INVENTORY_AVAILABILITY, availabilityEventId))
                .isTrue();
        assertThat(stockQuantStore.findById(stockQuantId))
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
        MovementFixtures.saveConfirmedPickingOrder(
                orderRepository,
                jdbcTemplate,
                OrderFixtures.backorderedOrder(orderId, "SKU-1", 3, receivedAt, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        stockReceiptApplicationFacade.confirm(receiptRequest(eventId, 3));

        UUID availabilityEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_outbox WHERE type = ?",
                UUID.class,
                StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE);

        // 明確在 canonical move-line persistence 注入失敗；既有 inbound line 已完成，constraint 只拒絕新列。
        jdbcTemplate.execute(
                "ALTER TABLE stock_move_lines ADD CONSTRAINT ck_test_reject_new_move_line CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> inventoryDrain().drain())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE stock_move_lines DROP CONSTRAINT ck_test_reject_new_move_line");
        }

        assertThat(inboxClaimExists(
                        ReservationAssignmentEventSubscriptions.INVENTORY_AVAILABILITY, availabilityEventId))
                .isFalse();
        assertThat(countReceiptRequests(eventId)).isEqualTo(1);
        // 收貨是已完成的獨立 checkpoint；後續 outbound 配貨失敗不能撤銷實際到貨。
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        outcomeDrain().drain();
        assertThat(orderRepository.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        // Assignment 失敗不得改掉已登記的 movement intent，也不得留下部分 reservation。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CONFIRMED");
        // inbound execution 與 availability Outbox 保留，讓事件重試或 scheduler 日後收斂。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE source_line_id IS NULL AND state = 'DONE'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 receiptId 綁定不同收貨內容時拒絕，不得再異動庫存")
    void shouldRejectAReceiptIdReusedForDifferentContent() {
        UUID stockQuantId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 0, 0));

        stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 3));

        assertThatThrownBy(() -> stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 4)))
                .isInstanceOf(StockReceiptRequestConflictException.class)
                .hasMessageContaining(receiptId.toString());

        assertThat(countReceiptRequests(receiptId)).isEqualTo(1);
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isZero();
        });
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE source_line_id IS NULL AND state = 'DONE'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("收貨業務失敗時 request claim、庫存與 Outbox 必須一起回滾")
    void shouldRollBackTheReceiptClaimWhenBusinessHandlingFails() {
        UUID receiptId = UUID.randomUUID();
        jdbcTemplate.update("DELETE FROM stock_operation_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);

        assertThatThrownBy(() -> stockReceiptApplicationFacade.confirm(receiptRequest(receiptId, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no inbound operation type");

        assertThat(countReceiptRequests(receiptId)).isZero();
        assertThat(count("stock_operations")).isZero();
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
