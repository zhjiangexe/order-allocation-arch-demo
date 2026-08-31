package com.flowzati.archone.integration.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.ordering.application.invocation.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import java.util.List;
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
 * 驗證 outbox row 的領域身分與傳輸決策確實分離：不論分區策略為何，Order aggregate 的
 * 事件一律以 orderId 作為 {@code aggregateid}，SKU 只出現在 {@code partition_key}。
 *
 * <p>以「用 orderId 查得到這張訂單發布過的全部事件」作為斷言手法，是因為那是這個性質
 * 最直接的可觀察後果——把 partition key 塞在 {@code aggregateid} 的舊實作在 sku 策略下
 * 會漏掉下單事件。這裡不預設任何查詢端存在，純粹是對 schema 誠實度的迴歸測試。
 */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = {"spring.kafka.listener.auto-startup=false", "archone.allocation.partition-key-strategy=stock"},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class OutboxAggregateQueryIntegrationTest {

    private static final String SKU = "SKU-CHAIN";

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.InventoryEventDrainFactory inventoryEventDrainFactory;

    @Autowired
    private PlaceOrderUsecase placeOrderUsecase;

    @Autowired
    private ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

    @Autowired
    private AllocationOrderLifecycleEventDriver consumer;

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
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-CHAIN");
    }

    @Test
    @DisplayName("stock 分區策略下，仍能以 orderId 查回該訂單完整的事件因果鏈")
    void shouldReturnFullEventChainByOrderIdUnderSkuPartitionStrategy() throws Exception {
        stockQuantStore.save(StockFixtures.unexpiredBatch(SKU, 0, 0));

        UUID orderId = placeOrder();
        attemptAllocation(orderId);
        confirmStockReceipt();

        outcomeDrain().drain();
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED));

        assertThat(eventTypesFor(orderId))
                .containsExactly(
                        OrderPlacedIntegrationEvent.EVENT_TYPE, OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
    }

    @Test
    @DisplayName("stock 分區策略下，下單事件的 partition key 是 (貨主, 倉)，配置結果事件是 orderId")
    void shouldKeepDeliveryKeysSeparateFromAggregateIdentity() throws Exception {
        stockQuantStore.save(StockFixtures.unexpiredBatch(SKU, 0, 0));

        UUID orderId = placeOrder();
        attemptAllocation(orderId);
        confirmStockReceipt();

        // 下單事件的 key 是爭用群組（貨主/倉/SKU），配貨結果事件維持 orderId。
        String contentionKey = com.flowzati.archone.contracts.stock.v1.StockContentionKey.of(
                OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID);
        assertThat(partitionKeysFor(orderId)).containsExactly(contentionKey, orderId.toString());
    }

    private UUID placeOrder() {
        Order placed = placeOrderUsecase.placeOrder(new PlaceOrderCommand(
                OrderFixtures.OWNER_ID,
                "EXT-" + UUID.randomUUID(),
                "100",
                "台北市中正區重慶南路一段 122 號",
                java.time.LocalDate.of(2026, 8, 1),
                OrderFixtures.DISPATCH_BY,
                OrderFixtures.RELEASE_PRIORITY,
                OrderFixtures.FACILITY_ID,
                null,
                java.util.List.of(new PlaceOrderCommand.Line(SKU, 3))));
        // 下單當下 StockQuant 的 ATP 是 0，配置決策要等這筆下單事件被 allocation 消費才發生。
        assertThat(placed.getStatus()).isEqualTo(OrderStatus.PENDING);
        return placed.getId();
    }

    private void attemptAllocation(UUID orderId) throws Exception {
        Order currentOrder = orderStore.findById(orderId).orElseThrow();
        consumer.consume(new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, currentOrder.getReceivedAt()));
        outcomeDrain().drain();
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    }

    private void confirmStockReceipt() {
        com.flowzati.archone.testsupport.StockReceiptFixture.confirm(confirmStockReceiptUsecase, SKU, 3);
        inventoryEventDrainFactory.create().drain();
    }

    private List<String> eventTypesFor(UUID orderId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM event_outbox WHERE aggregatetype = ? AND aggregateid = ? ORDER BY timestamp",
                String.class,
                OrderingAggregateTypes.ORDER,
                orderId.toString());
    }

    private List<String> partitionKeysFor(UUID orderId) {
        return jdbcTemplate.queryForList(
                "SELECT partition_key FROM event_outbox WHERE aggregatetype = ? AND aggregateid = ? ORDER BY timestamp",
                String.class,
                OrderingAggregateTypes.ORDER,
                orderId.toString());
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
}
