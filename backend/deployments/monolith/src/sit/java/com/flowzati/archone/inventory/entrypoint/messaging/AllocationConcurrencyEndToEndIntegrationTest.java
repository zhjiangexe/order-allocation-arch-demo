package com.flowzati.archone.inventory.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationSubscriberIds;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, AllocationConcurrencyEndToEndIntegrationTest.ConflictConfiguration.class})
class AllocationConcurrencyEndToEndIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

    @Autowired
    private com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver consumer;

    @Autowired
    private OrderStore orderStore;

    @Autowired
    private StockQuantStore stockQuantStore;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AllocationConflictInjector conflictInjector;

    @AfterEach
    void clearDatabase() {
        conflictInjector.reset();
        SitDatabase.clear(jdbcTemplate);
    }

    /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
    @BeforeEach
    void seedCatalogForOrders() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-CONCURRENT", "SKU-EXHAUSTED");
    }

    @Test
    @DisplayName("兩張訂單競爭同一 ATP 時應按 canonical lock order 收斂為一張配置、一張欠單")
    void shouldSerializeConcurrentAssignmentWithoutOverselling() throws Exception {
        UUID stockQuantId = UUID.randomUUID();
        UUID firstOrderId = IdGenerator.nextId();
        UUID secondOrderId = IdGenerator.nextId();
        Instant receivedAt = Instant.now().minusSeconds(1);
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-CONCURRENT", 3, 0));
        orderStore.save(OrderFixtures.pendingOrder(firstOrderId, "SKU-CONCURRENT", 3, receivedAt));
        orderStore.save(OrderFixtures.pendingOrder(secondOrderId, "SKU-CONCURRENT", 3, receivedAt));

        OrderPlacedIntegrationEvent firstEvent =
                new OrderPlacedIntegrationEvent(UUID.randomUUID(), firstOrderId, receivedAt);
        OrderPlacedIntegrationEvent secondEvent =
                new OrderPlacedIntegrationEvent(UUID.randomUUID(), secondOrderId, receivedAt);
        conflictInjector.blockFirstTwoAllocationAttempts();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> consume(firstEvent));
            Future<?> second = executor.submit(() -> consume(secondEvent));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // 先把配貨結果餵回 ordering，訂單狀態才會推進——讀狀態一定要在這之後。
        outcomeDrain().drain();

        List<OrderStatus> statuses = List.of(
                orderStore.findById(firstOrderId).orElseThrow().getStatus(),
                orderStore.findById(secondOrderId).orElseThrow().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(OrderStatus.ALLOCATED, OrderStatus.PENDING);
        assertThat(stockQuantStore.findById(stockQuantId)).hasValueSatisfying(pool -> {
            assertThat(pool.getReservedQuantity()).isEqualTo(3);
            assertThat(pool.getReservedQuantity()).isLessThanOrEqualTo(pool.getOnHandQuantity());
        });
        assertThat(tableCount("stock_operations")).isEqualTo(2);
        assertThat(tableCount("stock_move_lines")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT reserved_quantity
                             - COALESCE((
                                   SELECT SUM(move_line.quantity)
                                     FROM stock_move_lines move_line
                                     JOIN stock_moves move ON move.id = move_line.move_id
                                    WHERE move_line.stock_pool_id = ?
                                      AND move.state = 'ASSIGNED'
                               ), 0)
                          FROM stock_pools
                         WHERE id = ?
                        """, Integer.class, stockQuantId, stockQuantId))
                .isZero();
        // 恰好一張拿到預留：兩張都拿到代表超賣，都沒拿到代表兩張都白白重試到耗盡。
        assertThat(!heldBy(firstOrderId).isEmpty() ^ !heldBy(secondOrderId).isEmpty())
                .isTrue();
        assertThat(inboxClaimExists(AllocationSubscriberIds.ORDER_PLACEMENT, firstEvent.getEventId()))
                .isTrue();
        assertThat(inboxClaimExists(AllocationSubscriberIds.ORDER_PLACEMENT, secondEvent.getEventId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT type FROM event_outbox", String.class))
                .contains(OrderAllocationCommittedIntegrationEvent.EVENT_TYPE);
        assertThat(conflictInjector.invocations()).isEqualTo(2);
    }

    @Test
    @DisplayName("三次技術衝突耗盡時應回滾業務資料與 Inbox Outbox 並記錄 metric")
    void shouldRollbackAllocationWhenConcurrencyRetryIsExhausted() {
        UUID stockQuantId = UUID.randomUUID();
        UUID orderId = IdGenerator.nextId();
        Instant receivedAt = Instant.now().minusSeconds(1);
        stockQuantStore.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-EXHAUSTED", 3, 0));
        orderStore.save(OrderFixtures.pendingOrder(orderId, "SKU-EXHAUSTED", 3, receivedAt));
        OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
        double metricBefore = exhaustedMetricCount();
        conflictInjector.failFirstAllocationAttempts(3);

        assertThatThrownBy(() -> consume(event)).isInstanceOf(OptimisticLockingRetryExhaustedException.class);

        assertThat(conflictInjector.invocations()).isEqualTo(3);
        assertThat(conflictInjector.transactionIds()).hasSize(3).doesNotHaveDuplicates();
        outcomeDrain().drain();
        assertThat(orderStore.findById(orderId))
                .hasValueSatisfying(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(stockQuantStore.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
        assertThat(heldBy(orderId)).isEmpty();
        assertThat(inboxClaimExists(AllocationSubscriberIds.ORDER_PLACEMENT, event.getEventId()))
                .isFalse();
        assertThat(tableCount("event_outbox")).isZero();
        assertThat(exhaustedMetricCount()).isEqualTo(metricBefore + 1.0);
    }

    private void consume(OrderPlacedIntegrationEvent event) {
        consumer.consume(event);
    }

    private double exhaustedMetricCount() {
        var counter = meterRegistry
                .find("order_allocation_retry_exhausted_total")
                .tag("operation", "allocate-order")
                .counter();
        return counter == null ? 0.0 : counter.count();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class ConflictConfiguration {

        @Bean
        AllocationConflictInjector allocationConflictInjector(JdbcTemplate jdbcTemplate) {
            return new AllocationConflictInjector(jdbcTemplate);
        }
    }

    @Aspect
    static class AllocationConflictInjector {

        private final JdbcTemplate jdbcTemplate;
        private final List<Long> transactionIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile int forcedFailures;
        private volatile CountDownLatch concurrentAttempts;

        AllocationConflictInjector(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void blockFirstTwoAllocationAttempts() {
            concurrentAttempts = new CountDownLatch(2);
        }

        void failFirstAllocationAttempts(int attempts) {
            forcedFailures = attempts;
        }

        // 切在 canonical assignment transaction 上；proposal 已經完成，真正的 operation/move/quant
        // revalidation 與 write 尚未發生，適合同步兩個 stale proposals 並注入 rollback failure。
        //
        // **切點是字串，指錯不會編譯失敗，只會靜默匹配不到任何東西**——那時每一條斷言都仍然
        // 執行，只是重試次數變成 0。元件改名或搬家時，這一行必須跟著改。
        @Around("execution(* com.flowzati.archone.inventory.reservation.application.service."
                + "StockAllocationCommitter.commit(..))")
        public Object injectConflict(ProceedingJoinPoint joinPoint) throws Throwable {
            int invocation = invocations.incrementAndGet();
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            CountDownLatch latch = concurrentAttempts;
            if (latch != null && invocation <= 2) {
                latch.countDown();
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent allocation attempts did not reach the barrier");
                }
            }

            Object result = joinPoint.proceed();
            if (invocation <= forcedFailures) {
                throw new OptimisticLockingFailureException("forced allocation conflict");
            }
            return result;
        }

        int invocations() {
            return invocations.get();
        }

        List<Long> transactionIds() {
            return List.copyOf(transactionIds);
        }

        void reset() {
            transactionIds.clear();
            invocations.set(0);
            forcedFailures = 0;
            concurrentAttempts = null;
        }
    }

    /**
     * 這張單目前鎖住了哪些量。
     *
     * <p>路徑是 source → operation → move → move line。回的是清單而不是單筆：一段 move
     * 跨三批就有三條明細。
     */
    private java.util.List<MovementFixtures.HeldQuantity> heldBy(java.util.UUID orderId) {
        return MovementFixtures.heldBy(jdbcTemplate, orderId);
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
