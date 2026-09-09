package com.flowzati.archone.inventory.reservation.assignment.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.store.OwnerAllocationPolicyStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
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

/**
 * 釘住 FIFO 保證的<strong>範圍</strong>：相同 owner、location、SKU 已有較早 confirmed operation 時，
 * 後到的新單即使當下 ATP 足夠也不得插隊，必須一起進入等待佇列。
 *
 * <p><b>這支測試存在的理由是 availability event 與新單事件之間有時間空窗。</b>Inbox 只能去重
 * 同一訊息，不能阻止後到的新訂單在空窗中直接消耗留下的 ATP；因此初次配貨也必須看相同 scope
 * 是否已有更早且 SKU 相交的 confirmed operation。
 *
 * <p>數字刻意讓插隊直接可見：舊單要 100，兩次收貨合計正好 100；中間的新單要 10。正確結果
 * 是舊單取得 100、新單繼續等待，而不是新單先拿 10、舊單最後短少 10。
 *
 * <p>與 {@code AllocationFifoAvailabilityIncreaseBatchIntegrationTest} 的分工：那支測的是佇列
 * <em>內部</em>的順序（嚴格 FIFO 與 head-of-line blocking），這支測的是佇列<em>外部</em>
 * 的邊界（初次配貨也必須服從既有等待佇列）。
 */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationFifoGuaranteeScopeIntegrationTest {

    private static final String SKU = "SCOPE-SKU";
    private static final int QUEUED_ORDER_QUANTITY = 100;
    private static final int FIRST_AVAILABILITY_INCREASE = 30;
    private static final int NEW_ORDER_QUANTITY = 10;
    private static final int SECOND_AVAILABILITY_INCREASE = 70;

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.InventoryEventDrainFactory inventoryEventDrainFactory;

    @Autowired
    private com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver consumer;

    @Autowired
    private ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

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

    /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔，寫入訂單前主檔必須先存在。 */
    @Autowired
    private OwnerAllocationPolicyStore ownerAllocationPolicyStore;

    @BeforeEach
    void seedCatalogForOrders() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, SKU);
        ownerAllocationPolicyStore.save(OrderFixtures.OWNER_ID, AllocationSequencePolicy.FIFO);
    }

    @Test
    @DisplayName("availability event 的空窗中，新單不得使用餘量繞過較早的相交 demand")
    void shouldKeepANewOrderBehindAnOlderQueuedOrder() throws Exception {
        // Step 1：一個空的庫存池，與一張已經排隊很久、需求 100 的缺貨訂單。
        UUID stockQuantId = UUID.randomUUID();
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, SKU, 0, 0));
        UUID queuedOrderId = seedQueuedOrder();

        // Step 2：補進 30。佇列的 head 要 100，head-of-line blocking 讓它配不到，
        // 這 30 個單位原封不動留在池裡。
        receive(FIRST_AVAILABILITY_INCREASE);

        assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(availableToPromise(stockQuantId)).isEqualTo(FIRST_AVAILABILITY_INCREASE);

        // Step 3：此時一張全新的訂單到達，需求 10。即使 ATP 足夠，它仍須先看相同
        // owner/location/SKU 的 waiting head。
        UUID newOrderId = placeNewOrder();

        // Step 4：新單也進 waiting queue，30 件不被後到需求取走。
        assertThat(statusOf(newOrderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(availableToPromise(stockQuantId)).isEqualTo(FIRST_AVAILABILITY_INCREASE);

        // Step 5：再補 70，兩次補貨合計正好 100——恰好是舊單的需求量。
        receive(SECOND_AVAILABILITY_INCREASE);

        // Step 6：舊單先取得完整 100 件；新單繼續等待，不會因 arrival gap 插隊。
        assertThat(statusOf(queuedOrderId)).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(statusOf(newOrderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(availableToPromise(stockQuantId)).isZero();
    }

    private UUID seedQueuedOrder() {
        UUID orderId = IdGenerator.nextId();
        Instant backorderedAt = PostgreSQLTestConfiguration.NOW.minusSeconds(3600);
        MovementFixtures.saveConfirmedPickingOrder(
                orderStore,
                jdbcTemplate,
                OrderFixtures.backorderedOrder(
                        orderId, SKU, QUEUED_ORDER_QUANTITY, backorderedAt.minusSeconds(1), backorderedAt));
        return orderId;
    }

    private UUID placeNewOrder() throws Exception {
        UUID orderId = IdGenerator.nextId();
        Instant receivedAt = PostgreSQLTestConfiguration.NOW;
        orderStore.save(OrderFixtures.pendingOrder(orderId, SKU, NEW_ORDER_QUANTITY, receivedAt));
        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        consumer.consume(event);
        return orderId;
    }

    private void receive(int quantity) {
        com.flowzati.archone.testsupport.StockReceiptFixture.confirm(confirmStockReceiptUsecase, SKU, quantity);
        inventoryEventDrainFactory.create().drain();
    }

    private OrderStatus statusOf(UUID orderId) {
        // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
        // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
        outcomeDrain().drain();

        return orderStore.findById(orderId).orElseThrow().getStatus();
    }

    private int availableToPromise(UUID stockQuantId) {
        return stockQuantStore.findById(stockQuantId).orElseThrow().availableToPromise();
    }

    private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
        return outcomeDrainFactory.create();
    }
}
