package com.flowzati.archone.inventory.reservation.assignment.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.reservation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.reservation.application.usecase.ReconcileStockOperationBacklogUsecase;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Demo-02：1,000 張已排隊的同 SKU 缺貨訂單，被循序到達的 StockReplenished 事件喚醒後的
 * 嚴格 FIFO 批次配置決策。
 *
 * <p>驗證範圍：循序（非併發）補貨事件觸發的批次配置演算法，在有意義的排隊量體下依然嚴格
 * 遵守 FIFO 與 head-of-line blocking——第一波補貨不足時正確整批停止在第一張補不滿的訂單，
 * 且不跳過去配置後面數量更小、原本配得起的訂單；第二波補貨到位後，先前被卡住的訂單也能
 * 正確恢復配置。不涉及多個補貨事件同時到達的併發競爭，也不是 production
 * throughput/latency benchmark。
 */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationFifoAvailabilityIncreaseBatchIntegrationTest {

    private static final int MAX_ATTEMPTS_PER_RUN = 200;
    private static final long MAX_RECONCILIATION_RUN_DURATION_MS = 45_000;
    /** Scheduler reconciliation 的測試硬上限，避免錯誤的收斂條件讓測試一直執行。 */
    private static final int MAX_RECONCILIATION_ROUNDS = 1_000 / MAX_ATTEMPTS_PER_RUN + 2;

    private static final String FIFO_SKU = "FIFO-SKU";
    private static final int FITTING_ORDERS_BEFORE_BLOCKER = 500;
    private static final int FITTING_ORDERS_AFTER_BLOCKER = 499;
    private static final int TOTAL_ORDERS = FITTING_ORDERS_BEFORE_BLOCKER + 1 + FITTING_ORDERS_AFTER_BLOCKER;
    private static final int BLOCKER_QUANTITY = 999;
    private static final int FIRST_AVAILABILITY_INCREASE = FITTING_ORDERS_BEFORE_BLOCKER;
    private static final int SECOND_AVAILABILITY_INCREASE = BLOCKER_QUANTITY + FITTING_ORDERS_AFTER_BLOCKER;

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @Autowired
    private com.flowzati.archone.testsupport.InventoryEventDrainFactory inventoryEventDrainFactory;

    @Autowired
    private ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

    private ReconcileStockOperationBacklogUsecase reconcileStockOperationBacklogUsecase;

    @Autowired
    private StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore;

    @Autowired
    private StockOperationAssignmentCoordinator stockOperationAssignmentCoordinator;

    @Autowired
    private BusinessClock appClock;

    @Autowired
    private OrderStore orderStore;

    @Autowired
    private StockQuantStore stockQuantStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
    @BeforeEach
    void seedCatalogForOrders() {
        // test profile 刻意不建立／啟動 production scheduler bean，避免背景 tick 介入；本 SIT
        // 直接建立同一個 entrypoint 並明確驅動每一輪，production condition 另由 unit test 保護。
        reconcileStockOperationBacklogUsecase = new ReconcileStockOperationBacklogUsecase(
                stockOperationAssignmentBacklogStore,
                stockOperationAssignmentCoordinator,
                appClock,
                MAX_ATTEMPTS_PER_RUN,
                MAX_RECONCILIATION_RUN_DURATION_MS);
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "FIFO-SKU");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("循序補貨事件喚醒 1,000 張排隊缺貨訂單時應嚴格 FIFO 批次配置，且不足時正確卡住、補足後正確恢復")
    void shouldAllocateStrictFifoQueueAcrossSequentialReplenishments() {
        // Step 1：種入一個目前完全沒有庫存的 StockQuant，以及 1,000 張已經在排隊的 BACKORDERED
        // Order。FIFO 排序靠 backorderedSince 逐筆遞增保證穩定，跟 production 的
        // `ORDER BY backordered_since ASC, id ASC` 對齊。額外記住三個關鍵位置的 orderId
        // （最早、blocker、最晚），之後用來做「不是只看聚合數字」的精準身分驗證——
        // 因為除了 blocker 之外每張訂單 quantity 都是 1，光看總數／總量無法分辨
        // FIFO 有沒有選對「哪幾張」，只能證明選對「幾張」。
        UUID stockQuantId = UUID.randomUUID();
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, FIFO_SKU, 0, 0));
        BackorderQueue queue = seedBackorderQueue();

        // Step 2：第一次補貨，數量精準等於前 500 張的總和，逼出「blocker 之後全部停止」的
        // 批次決策，而不是靠隨機數量碰運氣。
        //
        // **一次 availability 事件不再喚醒整個佇列。** 它只做首輪；超過張數上限的剩餘工作由
        // Scheduler 後續掃描同一個 scope。SIT 關閉自動排程，所以在這裡直接驅動 scheduler method。
        receive(FIRST_AVAILABILITY_INCREASE);
        int firstPhaseRounds = reconcileWithSchedulerUntilStable();

        // 分多輪是這個 change 的重點之一：如果只跑了一輪，代表上限沒有生效，而後面那些
        // 「收斂後的狀態」斷言就退化成了舊行為的斷言。
        assertThat(firstPhaseRounds)
                .withFailMessage("第一波補貨應由 Scheduler 分多輪收斂，實際只有 %d 輪", firstPhaseRounds)
                .isGreaterThan(0);

        // Step 3：對帳第一階段——前 500 張應該已配置，blocker 與其後 499 張仍應卡在 BACKORDERED。
        assertReconciledState(
                stockQuantId,
                new ExpectedSnapshot(
                        /* allocated */ FITTING_ORDERS_BEFORE_BLOCKER,
                        /* backordered */ TOTAL_ORDERS - FITTING_ORDERS_BEFORE_BLOCKER,
                        /* reservationCount */ FITTING_ORDERS_BEFORE_BLOCKER,
                        /* reservationQuantity */ FIRST_AVAILABILITY_INCREASE,
                        /* onHand */ FIRST_AVAILABILITY_INCREASE,
                        /* reserved */ FIRST_AVAILABILITY_INCREASE,
                        /* inboxCount */ 2,
                        /* outboxAllocatedCount */ FITTING_ORDERS_BEFORE_BLOCKER));
        assertThat(statusOf(queue.firstOrderId())).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(statusOf(queue.blockerOrderId())).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(queue.lastOrderId())).isEqualTo(OrderStatus.PENDING);

        // Step 4：第二次（循序、非併發）補貨，數量等於 blocker 與其後 499 張的總和，
        // 驗證「喚醒佇列」的後半段——先前被 head-of-line blocking 卡住的訂單，補貨到位後
        // 應該能正確恢復配置，而不只是第一波卡住就結束驗證。
        receive(SECOND_AVAILABILITY_INCREASE);
        reconcileWithSchedulerUntilStable();

        // Step 5：對帳第二階段——整個佇列應該全部配置完畢，沒有訂單被遺漏或重複配置。
        int totalAvailableIncrease = FIRST_AVAILABILITY_INCREASE + SECOND_AVAILABILITY_INCREASE;
        assertReconciledState(
                stockQuantId,
                new ExpectedSnapshot(
                        /* allocated */ TOTAL_ORDERS,
                        /* backordered */ 0,
                        /* reservationCount */ TOTAL_ORDERS,
                        /* reservationQuantity */ totalAvailableIncrease,
                        /* onHand */ totalAvailableIncrease,
                        /* reserved */ totalAvailableIncrease,
                        /* inboxCount */ 4,
                        /* outboxAllocatedCount */ TOTAL_ORDERS));
        assertThat(statusOf(queue.firstOrderId())).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(statusOf(queue.blockerOrderId())).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(statusOf(queue.lastOrderId())).isEqualTo(OrderStatus.ALLOCATED);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("blocker 卡在隊首且庫存還有量時 Scheduler 應在無進展後停止本次測試收斂")
    void stopsInsteadOfLoopingWhenTheHeadOfLineIsBlockedWithStockRemaining() {
        // 這個情境是「讀到幾張」與「配到幾張」唯一會分歧的地方，也是唯一能分辨終止條件寫對沒有
        // 的地方：
        //
        //   讀到 = 上限（每輪都讀滿），配到 = 0（FIFO 停在隊首那張配不滿的單）
        //
        // Scheduler 每次 tick 仍會重新看到這個 scope，但單次交易必須安全地零進展返回，不能跳過
        // blocker 去配後面的單。上一支測試碰不到這件事，因為那裡庫存剛好用完。
        UUID stockQuantId = UUID.randomUUID();
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, FIFO_SKU, 0, 0));
        Instant backorderedAt = Instant.now().minusSeconds(3600);
        int position = 0;
        UUID blockerOrderId = seedBackorderedOrder(BLOCKER_QUANTITY, backorderedAt, position++);
        for (int i = 0; i < MAX_ATTEMPTS_PER_RUN * 2; i++) {
            seedBackorderedOrder(1, backorderedAt, position++);
        }

        // 補的量餵不飽 blocker，但遠遠足夠餵飽它後面那些單——所以「庫存還有」與「配不到」同時成立。
        receive(BLOCKER_QUANTITY - 1);
        int rounds = reconcileWithSchedulerUntilStable();

        assertThat(rounds)
                .withFailMessage("blocker 卡住時不該有 productive scheduler round，實際有 %d 輪", rounds)
                .isZero();
        assertThat(statusOf(blockerOrderId)).isEqualTo(OrderStatus.PENDING);
        // 庫存一件都沒被動用：head-of-line blocking 不是「跳過去配小單」。
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("別的倉的更早訂單不得進入本輪——它們這次補貨滿足不了，卻會佔滿以張數計的上限")
    void excludesOrdersShippingFromAnotherWarehouse() {
        UUID stockQuantId = UUID.randomUUID();
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, FIFO_SKU, 0, 0));

        // **先種別的倉的單**，所以它們的 order_id 較小、在佇列裡排更前面。少了倉別篩選，它們
        // 會排在最前面被讀進來，然後因為查不到自己那個倉的批次而一張張被跳過——不會出錯，
        // 但整個上限就這樣被用光，真正配得到的單一張都輪不到。
        List<UUID> otherWarehouseOrders = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_ATTEMPTS_PER_RUN; i++) {
            UUID orderId = IdGenerator.nextId();
            MovementFixtures.saveConfirmedPickingOrder(
                    orderStore,
                    jdbcTemplate,
                    OrderFixtures.backorderedOrderAt(
                            OrderFixtures.OTHER_FACILITY_ID,
                            orderId,
                            OrderFixtures.OWNER_ID,
                            FIFO_SKU,
                            1,
                            Instant.now().minusSeconds(7200),
                            Instant.now().minusSeconds(7200)));
            otherWarehouseOrders.add(orderId);
        }
        UUID mine = seedBackorderedOrder(1, Instant.now().minusSeconds(60), 0);

        receive(1);
        reconcileWithSchedulerUntilStable();

        // 補的是本倉的一件，該配到的是本倉那張——即使它在佇列裡排在最後面。
        assertThat(statusOf(mine)).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(otherWarehouseOrders)
                .withFailMessage("別的倉的訂單不該被這次補貨碰到")
                .allSatisfy(id -> assertThat(statusOf(id)).isEqualTo(OrderStatus.PENDING));
    }

    /** 建立 FIFO 排序穩定的 1,000 張 BACKORDERED Order：前 500 張、blocker、後 499 張。 */
    private BackorderQueue seedBackorderQueue() {
        Instant firstBackorderedAt = Instant.now().minusSeconds(3600);
        int position = 0;
        UUID firstOrderId = seedBackorderedOrder(1, firstBackorderedAt, position++);
        for (int i = 1; i < FITTING_ORDERS_BEFORE_BLOCKER; i++) {
            seedBackorderedOrder(1, firstBackorderedAt, position++);
        }
        UUID blockerOrderId = seedBackorderedOrder(BLOCKER_QUANTITY, firstBackorderedAt, position++);
        UUID lastOrderId = null;
        for (int i = 0; i < FITTING_ORDERS_AFTER_BLOCKER; i++) {
            lastOrderId = seedBackorderedOrder(1, firstBackorderedAt, position++);
        }
        return new BackorderQueue(firstOrderId, blockerOrderId, lastOrderId);
    }

    private UUID seedBackorderedOrder(int quantity, Instant firstBackorderedAt, int fifoPosition) {
        UUID orderId = IdGenerator.nextId();
        Instant backorderedAt = firstBackorderedAt.plusMillis(fifoPosition);
        Order order = OrderFixtures.backorderedOrder(
                orderId, FIFO_SKU, quantity, backorderedAt.minusSeconds(1), backorderedAt);
        // 訂單與 canonical confirmed operation/moves 一起寫；moves 本身就是 queue。
        MovementFixtures.saveConfirmedPickingOrder(orderStore, jdbcTemplate, order);
        return orderId;
    }

    private OrderStatus statusOf(UUID orderId) {
        // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
        // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
        outcomeDrain().drain();

        return orderStore.findById(orderId).orElseThrow().getStatus();
    }

    private void assertReconciledState(UUID stockQuantId, ExpectedSnapshot expected) {
        // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
        // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
        outcomeDrain().drain();

        // 1) Order 結果：ALLOCATED／BACKORDERED 的張數要精準對上這個階段的預期。
        Integer allocatedCount = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'ALLOCATED'
        """, Integer.class, FIFO_SKU);
        Integer backorderedCount = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'PENDING'
        """, Integer.class, FIFO_SKU);
        assertThat(allocatedCount).isEqualTo(expected.allocated());
        assertThat(backorderedCount).isEqualTo(expected.backordered());

        // 2) 鎖定結果：明細筆數、總量都要精確等於這個階段累積補貨量，且 order_id 不重複。
        // 「還有效」不再是一個狀態欄位——**明細存在就代表鎖著**，釋放是刪除那一列。因此三個
        // 查詢都不帶條件；少了那個 WHERE 正是這次遷移在這裡的全部內容。
        //
        // 區域變數仍叫 reservation：命名收斂集中在第四個 change，這裡動它會讓「斷言一字未改」
        // 這件事變得難以核對。
        // **只數為需求鎖住的那些明細。** 入庫走搬運之後，stock_move_lines 同時是收貨的紀錄——
        // 不篩的話補進來的每一批都會被算成一筆預留。判準是「這條明細背後有訂單行」，那正是
        // 「為某張單鎖的」的定義；收貨的搬運沒有訂單行。
        Integer activeReservationCount = jdbcTemplate.queryForObject("""
        SELECT count(*)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
          JOIN stock_operations p ON p.id = m.stock_operation_id
         WHERE p.source_type IS NOT NULL
           AND m.state = 'ASSIGNED'
        """, Integer.class);
        Integer activeReservationQuantity = jdbcTemplate.queryForObject("""
        SELECT coalesce(sum(ml.quantity), 0)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
          JOIN stock_operations p ON p.id = m.stock_operation_id
         WHERE p.source_type IS NOT NULL
           AND m.state = 'ASSIGNED'
        """, Integer.class);
        Integer distinctReservedOrders = jdbcTemplate.queryForObject("""
        SELECT count(DISTINCT p.source_id)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
          JOIN stock_operations p ON p.id = m.stock_operation_id
         WHERE p.source_type IS NOT NULL
           AND m.state = 'ASSIGNED'
        """, Integer.class);
        assertThat(activeReservationCount).isEqualTo(expected.reservationCount());
        assertThat(activeReservationQuantity).isEqualTo(expected.reservationQuantity());
        assertThat(distinctReservedOrders).isEqualTo(expected.reservationCount());

        // 3) StockQuant 結果：on-hand 等於累積補貨量、reserved 全部用完、ATP 歸零。
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getOnHandQuantity()).isEqualTo(expected.onHand());
            assertThat(pool.getReservedQuantity()).isEqualTo(expected.reserved());
            assertThat(pool.availableToPromise()).isZero();
        });

        // 4) Inbox 結果：收貨命令與 availability 事件各 claim 一次；Scheduler 沒有 transport
        //    message，因此不寫 Inbox。
        Integer inboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM event_inbox", Integer.class);
        Integer duplicateInboxClaims = jdbcTemplate.queryForObject("""
        SELECT count(*)
          FROM (
                SELECT subscriber_id, event_id
                  FROM event_inbox
                 GROUP BY subscriber_id, event_id
                HAVING count(*) > 1
               ) duplicates
        """, Integer.class);
        assertThat(inboxCount).isGreaterThanOrEqualTo(expected.inboxCount());
        assertThat(duplicateInboxClaims).isZero();

        // 5) Outbox 結果：只有被配置的訂單各發一筆 OrderAllocationCommittedIntegrationEvent，這個測試
        //    情境全程只由 StockMove.CONFIRMED 表達待配貨，不發布額外的缺貨訂單事件。
        Integer allocatedOutboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE type = ?",
                Integer.class,
                OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
        assertThat(allocatedOutboxCount).isEqualTo(expected.outboxAllocatedCount());
    }

    private void receive(int quantity) {
        com.flowzati.archone.testsupport.StockReceiptFixture.confirm(confirmStockReceiptUsecase, FIFO_SKU, quantity);
        consumePendingAvailabilityEvents();
    }

    private void consumePendingAvailabilityEvents() {
        inventoryEventDrainFactory.create().drain();
    }

    /** 直接驅動 SIT 中停用的 scheduler，直到一輪沒有新增 allocation outcome。 */
    private int reconcileWithSchedulerUntilStable() {
        int productiveRounds = 0;
        for (int attempt = 0; attempt < MAX_RECONCILIATION_ROUNDS; attempt++) {
            int before = allocatedOutcomeCount();
            reconcileStockOperationBacklogUsecase.execute();
            int after = allocatedOutcomeCount();
            if (after == before) {
                return productiveRounds;
            }
            productiveRounds++;
        }
        throw new AssertionError(
                "Scheduler reconciliation did not stabilize within " + MAX_RECONCILIATION_ROUNDS + " rounds");
    }

    private int allocatedOutcomeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE type = ?",
                Integer.class,
                OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
    }

    /** FIFO 佇列中三個關鍵位置的 orderId，用來做不依賴聚合數字的精準身分驗證。 */
    private record BackorderQueue(UUID firstOrderId, UUID blockerOrderId, UUID lastOrderId) {}

    /** 某一階段補貨後，預期的持久化狀態快照。 */
    private record ExpectedSnapshot(
            int allocated,
            int backordered,
            int reservationCount,
            int reservationQuantity,
            int onHand,
            int reserved,
            int inboxCount,
            int outboxAllocatedCount) {}

    private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
        return outcomeDrainFactory.create();
    }
}
