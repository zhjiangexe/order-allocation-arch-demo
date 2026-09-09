package com.flowzati.archone.inventory.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.StockOperationLifecycleIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.usecase.ReleaseStockOperationUsecase;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationSubscriberIds;
import com.flowzati.archone.inventory.balance.application.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.invocation.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.service.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.MovementCancellationEventSubscriptions;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.time.Instant;
import java.util.Map;
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

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationWorkflowEndToEndIntegrationTest {

    @Autowired
    private com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver consumer;

    @Autowired
    private StockReceiptApplicationFacade stockReceiptApplicationFacade;

    @Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @Autowired
    private com.flowzati.archone.testsupport.InventoryEventDrainFactory inventoryEventDrainFactory;

    @Autowired
    private OrderStore orderStore;

    @Autowired
    private StockQuantStore stockQuantStore;

    @Autowired
    private ReleaseStockOperationUsecase releaseStockOperationUsecase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
    @BeforeEach
    void seedCatalogForOrders() {
        OrderFixtures.seedCatalog(
                jdbcTemplate,
                OrderFixtures.OWNER_ID,
                "SKU-AVAILABLE",
                "SKU-FIFO",
                "SKU-PARTIALLY-RESERVED",
                "SKU-BASKET-A",
                "SKU-BASKET-B");
    }

    @Test
    @DisplayName("下單整合事件應完成配置、寫入 Inbox 並建立 Outbox")
    void shouldAllocateOrderFromKafkaIntegrationEventAndWriteOutbox() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(1);
        orderStore.save(OrderFixtures.pendingOrder(orderId, "SKU-AVAILABLE", 3, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-AVAILABLE", 10, 0));

        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        consumer.consume(event);
        UUID allocationOutcomeEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_outbox WHERE type = ? ORDER BY timestamp",
                UUID.class,
                OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
        assertThat(tableCount("event_outbox")).isOne();

        // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
        // Debezium，所以這裡自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
        //
        // 這一步不只是為了讓斷言通過：它同時驗證 ordering 的 consumer 真的消費得了那些事件。
        assertThat(outcomeDrain().drain()).isPositive();

        assertThat(inboxClaimExists(AllocationSubscriberIds.ORDER_PLACEMENT, event.getEventId()))
                .isTrue();
        assertThat(inboxClaimExists(OrderingEventSubscriptions.ALLOCATION_RESULTS, allocationOutcomeEventId))
                .isTrue();

        // 重新投遞同一則 outcome 時仍會走完整 typed chain，但 Inbox 會在 handler 前擋掉重複效果。
        assertThat(outcomeDrain().drain()).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_inbox WHERE subscriber_id = ? AND event_id = ?",
                        Integer.class,
                        OrderingEventSubscriptions.ALLOCATION_RESULTS,
                        allocationOutcomeEventId))
                .isOne();
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(3));
        assertThat(heldBy(orderId)).singleElement().satisfies(held -> {
            assertThat(held.stockQuantId()).isEqualTo(stockQuantId);
            assertThat(held.quantity()).isEqualTo(3);
        });
        // 搬運在收單那一刻就建好了，這裡是它被轉成已鎖定。
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("ASSIGNED");
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT type, route, aggregateid FROM event_outbox WHERE type = ?",
                OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
        assertThat(outbox)
                .containsEntry("type", OrderAllocationCommittedIntegrationEvent.EVENT_TYPE)
                .containsEntry("route", AllocationEventDestinations.ALLOCATION_EVENTS)
                .containsEntry("aggregateid", orderId.toString());
    }

    @Test
    @DisplayName("取消整合事件應釋放有效 Reservation 與 ATP")
    void shouldReleaseActiveReservationFromKafkaCancellationEvent() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        Instant reservedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(1);
        Order order = OrderFixtures.allocatedOrder(
                orderId, "SKU-PARTIALLY-RESERVED", 4, reservedAt.minusSeconds(1), reservedAt);
        orderStore.save(order);
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-PARTIALLY-RESERVED", 10, 0));
        var scenario = MovementFixtures.seedAssignedPicking(jdbcTemplate, order, stockQuantId, 4);

        OrderCancelledIntegrationEvent event =
                new OrderCancelledIntegrationEvent(UUID.randomUUID(), orderId, PostgreSQLTestConfiguration.NOW);
        consumer.consume(event);
        // lost acknowledgement 後同一 event replay 必須由 Inbox 與 operation identity 共同維持 exactly-once。
        consumer.consume(event);

        assertThat(inboxClaimExists(MovementCancellationEventSubscriptions.ORDER_CANCELLATIONS, event.getEventId()))
                .isTrue();
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
        // Active move lines 被刪除；released reservation history 不留在 stock tables。
        assertThat(heldBy(orderId)).isEmpty();
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM stock_move_lines line
                          JOIN stock_moves move ON move.id = line.move_id
                         WHERE move.stock_operation_id = ?
                        """, Integer.class, scenario.stockOperationId()))
                .isZero();
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT state, stock_operation_id
                          FROM stock_operation_cancellations
                         WHERE stock_operation_id = ?
                        """, scenario.stockOperationId()))
                .containsEntry("state", "COMPLETED")
                .containsEntry("stock_operation_id", scenario.stockOperationId());
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT route, aggregateid,
                               payload::jsonb ->> 'action' AS action,
                               payload::jsonb #>> '{moves,0,batches,0,stockQuantId}' AS stock_quant_id,
                               payload::jsonb #>> '{moves,0,batches,0,quantity}' AS quantity
                          FROM event_outbox
                         WHERE type = ?
                        """, StockOperationLifecycleIntegrationEvent.EVENT_TYPE))
                .containsEntry("route", InventoryEventDestinations.STOCK_OPERATION_EVENTS)
                .containsEntry("aggregateid", scenario.stockOperationId().toString())
                .containsEntry("action", "CANCELLED")
                .containsEntry("stock_quant_id", stockQuantId.toString())
                .containsEntry("quantity", "4");
    }

    @Test
    @DisplayName("released operation 保留相同 move identity，並可重新指派")
    void shouldReallocationCommitterAfterExplicitRelease() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(2);
        orderStore.save(OrderFixtures.pendingOrder(orderId, "SKU-AVAILABLE", 3, receivedAt));
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-AVAILABLE", 10, 0));

        consumer.consume(new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt));
        UUID stockOperationId = jdbcTemplate.queryForObject(
                "SELECT id FROM stock_operations WHERE source_type = 'ORDER' AND source_id = ?",
                UUID.class,
                orderId.toString());
        UUID moveId = jdbcTemplate.queryForObject(
                "SELECT id FROM stock_moves WHERE stock_operation_id = ?", UUID.class, stockOperationId);

        assertThat(releaseStockOperationUsecase.execute(stockOperationId, PostgreSQLTestConfiguration.NOW))
                .isTrue();
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        quant -> assertThat(quant.getReservedQuantity()).isZero());
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT payload::jsonb ->> 'action' AS action,
                               payload::jsonb #>> '{moves,0,batches,0,stockQuantId}' AS stock_quant_id,
                               payload::jsonb #>> '{moves,0,batches,0,quantity}' AS quantity
                          FROM event_outbox
                         WHERE type = ?
                        """, StockOperationLifecycleIntegrationEvent.EVENT_TYPE))
                .containsEntry("action", "RELEASED")
                .containsEntry("stock_quant_id", stockQuantId.toString())
                .containsEntry("quantity", "3");

        consumer.consume(new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt));

        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        quant -> assertThat(quant.getReservedQuantity()).isEqualTo(3));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, stockOperationId))
                .isEqualTo("ASSIGNED");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM stock_moves WHERE id = ?", String.class, moveId))
                .isEqualTo("ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines WHERE move_id = ?", Integer.class, moveId))
                .isOne();
    }

    @Test
    @DisplayName("同步收貨應只按嚴格 FIFO 配置可完整滿足的前段訂單")
    void shouldAllocateOnlyFifoPrefixAfterReceipt() throws Exception {
        UUID stockQuantId = UUID.randomUUID();
        UUID firstOrderId = IdGenerator.nextId();
        UUID secondOrderId = IdGenerator.nextId();
        Instant firstBackorderedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(4);
        Instant secondBackorderedAt = firstBackorderedAt.plusSeconds(1);
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-FIFO", 0, 0));
        MovementFixtures.saveConfirmedPickingOrder(
                orderStore, jdbcTemplate, backorderedOrder(firstOrderId, "SKU-FIFO", 3, firstBackorderedAt));
        MovementFixtures.saveConfirmedPickingOrder(
                orderStore, jdbcTemplate, backorderedOrder(secondOrderId, "SKU-FIFO", 3, secondBackorderedAt));

        receive("SKU-FIFO", 5);
        outcomeDrain().drain();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_receipt_requests", Integer.class))
                .isEqualTo(1);
        assertThat(orderStore.findById(firstOrderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        assertThat(orderStore.findById(secondOrderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(5);
            assertThat(pool.getReservedQuantity()).isEqualTo(3);
        });
        assertThat(heldBy(firstOrderId)).isNotEmpty();
        assertThat(heldBy(secondOrderId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE type = ?",
                        Integer.class,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE))
                .isOne();
    }

    @Test
    @DisplayName("取消的單不得被補貨喚醒，貨要落到它後面那張活著的單上")
    void shouldNotWakeACancelledOrderAndShouldGiveTheStockToTheNextLiveOne() throws Exception {
        // **這條守的是行為，不是某個查詢的謂詞。** 取消的單不再是需求，補貨因此看不見它——
        // 而驗證它的方式是「貨去了哪裡」，那個問題不管待配需求是由 view 推導、還是由搬運的
        // 狀態回答，都問得出來。
        //
        // 兩張單刻意都缺同一個 SKU、取消的那張排在前面、補的量只夠一張：
        // 若取消沒有被排除，FIFO 會讓已取消的那張先配到，活著的那張就拿不到貨——**兩個斷言
        // 會同時翻面**，而不是只有一個。
        UUID stockQuantId = UUID.randomUUID();
        UUID cancelledOrderId = IdGenerator.nextId();
        UUID liveOrderId = IdGenerator.nextId();
        Instant earlier = PostgreSQLTestConfiguration.NOW.minusSeconds(4);

        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-FIFO", 0, 0));

        // **取消的那張要走完整條路：先排進佇列，再讓取消事件把它的搬運取消。**
        //
        // 這一段在這個 change 裡變重了。舊模型下佇列是 demand_lines，它讀 orders.cancelled_at，
        // 所以「訂單被標成取消」本身就足以讓它離開佇列；只存一張 CANCELLED 的訂單就測得到。
        // 現在佇列讀的是搬運，而取消是**由取消事件把搬運轉成 CANCELLED**——不送那則事件，這張
        // 單就只是一張沒有搬運的單，不在佇列裡的理由與「被排除」無關，這條測試會變成恆真。
        // 先取消再一次寫入：訂單只存一次（第二次 save 會撞主鍵，聚合根的 version 不會自己回填）。
        // 最終狀態與「先排隊、後取消」完全相同——搬運存在、訂單已取消、取消事件尚未被消費。
        Order cancelled = backorderedOrder(cancelledOrderId, "SKU-FIFO", 3, earlier);
        cancelled.cancel(
                UUID.randomUUID(), PostgreSQLTestConfiguration.NOW.minusSeconds(2), "Integration test cancellation");
        MovementFixtures.saveConfirmedPickingOrder(orderStore, jdbcTemplate, cancelled);
        consumer.consume(new OrderCancelledIntegrationEvent(
                UUID.randomUUID(), cancelledOrderId, PostgreSQLTestConfiguration.NOW.minusSeconds(2)));

        MovementFixtures.saveConfirmedPickingOrder(
                orderStore, jdbcTemplate, backorderedOrder(liveOrderId, "SKU-FIFO", 3, earlier.plusSeconds(1)));

        receive("SKU-FIFO", 3);
        outcomeDrain().drain();

        assertThat(orderStore.findById(cancelledOrderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED));
        assertThat(heldBy(cancelledOrderId)).isEmpty();

        assertThat(orderStore.findById(liveOrderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        assertThat(heldBy(liveOrderId)).isNotEmpty();

        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(3));
    }

    @Test
    @DisplayName("跨兩個 SKU 的單只要一個不足就整張掛帳，另一個 SKU 的庫存一件都不得被預留")
    void shouldReserveNothingWhenOneSkuOfAMultiSkuOrderFallsShort() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID plentifulId = UUID.randomUUID();
        UUID scarceId = UUID.randomUUID();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(1);
        orderStore.save(OrderFixtures.pendingMultiSkuOrder(
                orderId,
                receivedAt,
                new java.util.LinkedHashMap<>(java.util.Map.of("SKU-BASKET-A", 10, "SKU-BASKET-B", 5))));
        stockQuantStore.save(StockFixtures.unexpiredBatch(plentifulId, "SKU-BASKET-A", 100, 0));
        stockQuantStore.save(StockFixtures.unexpiredBatch(scarceId, "SKU-BASKET-B", 3, 0));

        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        consumer.consume(event);
        outcomeDrain().drain();

        // 「有貨卻不配」正是 ship-complete 的內容：為一張出不去的單鎖住 A 的 10 件，只會讓後面
        // 一張本來出得了的單拿不到。整籃原子性必須在真實的資料庫路徑上成立，不只在領域測試裡。
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(heldBy(orderId)).isEmpty();
        assertThat(stockQuantStore.findById(plentifulId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
        assertThat(stockQuantStore.findById(scarceId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
    }

    @Test
    @DisplayName("跨兩個 SKU 的單在兩者都足夠時整張配到，兩條行各有自己的預留")
    void shouldAllocateTheWholeBasketWhenEverySkuIsCovered() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID firstPoolId = UUID.randomUUID();
        UUID secondPoolId = UUID.randomUUID();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(1);
        orderStore.save(OrderFixtures.pendingMultiSkuOrder(
                orderId,
                receivedAt,
                new java.util.LinkedHashMap<>(java.util.Map.of("SKU-BASKET-A", 10, "SKU-BASKET-B", 5))));
        stockQuantStore.save(StockFixtures.unexpiredBatch(firstPoolId, "SKU-BASKET-A", 100, 0));
        stockQuantStore.save(StockFixtures.unexpiredBatch(secondPoolId, "SKU-BASKET-B", 100, 0));

        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        consumer.consume(event);
        assertThat(outcomeDrain().drain()).isPositive();

        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        // 預留的粒度是行 × 批：兩條行各自有一筆，摺成一筆就丟掉了出貨時要的「哪一批為哪一行鎖」。
        assertThat(heldBy(orderId)).hasSize(2);
        assertThat(stockQuantStore.findById(firstPoolId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(10));
        assertThat(stockQuantStore.findById(secondPoolId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(5));
    }

    @Test
    @DisplayName("同一張單同一個 SKU 的兩行應以加總配一次，不得扣兩次")
    void shouldCountTwoLinesOfTheSameSkuOnceAsTheirSum() throws Exception {
        UUID orderId = IdGenerator.nextId();
        UUID poolId = UUID.randomUUID();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(1);
        Order order = Order.rehydrate(
                orderId,
                OrderFixtures.OWNER_ID,
                "EXT-" + orderId,
                OrderFixtures.deliveryTerms(),
                java.util.List.of(
                        com.flowzati.archone.ordering.domain.entity.OrderLine.create(
                                UUID.randomUUID(), 1, OrderFixtures.OWNER_ID, "SKU-BASKET-A", 4),
                        com.flowzati.archone.ordering.domain.entity.OrderLine.create(
                                UUID.randomUUID(), 2, OrderFixtures.OWNER_ID, "SKU-BASKET-A", 6)),
                OrderStatus.PENDING,
                receivedAt,
                null,
                null,
                null,
                null,
                null);
        orderStore.save(order);
        stockQuantStore.save(StockFixtures.unexpiredBatch(poolId, "SKU-BASKET-A", 10, 0));

        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        consumer.consume(event);
        assertThat(outcomeDrain().drain()).isPositive();

        // roadmap 曾記載一支缺 DISTINCT 的佇列查詢，會讓同一張單出現兩次而扣兩次量。取代它的
        // 兩段式查詢在結構上排除了這件事——但那是副作用而非目標，所以要有一支測試明確守著。
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(
                        allocated -> assertThat(allocated.getStatus()).isEqualTo(OrderStatus.ALLOCATED));
        assertThat(stockQuantStore.findById(poolId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(10));
        assertThat(heldBy(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("補 A 之後，缺 B 的隊首仍不配，且 A 一件都不得被預留")
    void shouldJudgeACandidatesOtherSkuAgainstItsOwnStock() throws Exception {
        UUID poolId = UUID.randomUUID();
        UUID orderId = IdGenerator.nextId();
        Instant backorderedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(4);
        stockQuantStore.save(StockFixtures.unexpiredBatch(poolId, "SKU-BASKET-A", 0, 0));
        Order order = OrderFixtures.pendingMultiSkuOrder(
                orderId,
                backorderedAt.minusSeconds(1),
                new java.util.LinkedHashMap<>(java.util.Map.of("SKU-BASKET-A", 1, "SKU-BASKET-B", 1)));
        MovementFixtures.saveConfirmedPickingOrder(orderStore, jdbcTemplate, order);

        receive("SKU-BASKET-A", 5);
        outcomeDrain().drain();

        // 喚醒是由 A 觸發的，但候選單還要 B——而 B 一批都沒有。只看被補的那個 SKU 的實作會在
        // 這裡把整張單配掉。
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(woken -> assertThat(woken.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(heldBy(orderId)).isEmpty();
        assertThat(stockQuantStore.findById(poolId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(5);
            assertThat(pool.getReservedQuantity()).isZero();
        });
    }

    private Order backorderedOrder(UUID orderId, String sku, int quantity, Instant backorderedAt) {
        return OrderFixtures.backorderedOrder(orderId, sku, quantity, backorderedAt.minusSeconds(1), backorderedAt);
    }

    private void receive(String sku, int quantity) {
        stockReceiptApplicationFacade.confirm(new StockReceiptRequest(
                UUID.randomUUID(),
                new ConfirmStockReceiptCommand(
                        OrderFixtures.OWNER_ID,
                        OrderFixtures.FACILITY_ID,
                        OrderFixtures.LOCATION_ID,
                        sku,
                        StockFixtures.ARRIVED_ON,
                        StockFixtures.EXPIRES_ON,
                        quantity)));
        inventoryEventDrainFactory.create().drain();
    }

    /**
     * 這張單目前鎖住了哪些量。
     *
     * <p>路徑是 demand → movement target → 明細，與 {@code CancelMovementsUsecase} 走同一條。回的是清單
     * 而不是單筆：一條行跨三批就有三條明細。
     */
    private java.util.List<MovementFixtures.HeldQuantity> heldBy(java.util.UUID orderId) {
        return MovementFixtures.heldBy(jdbcTemplate, orderId);
    }

    private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
        return outcomeDrainFactory.create();
    }

    private boolean inboxClaimExists(String subscriberId, UUID eventId) {
        return jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM event_inbox
         WHERE subscriber_id = ? AND event_id = ?
        """, Integer.class, subscriberId, eventId) == 1;
    }

    private int tableCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
