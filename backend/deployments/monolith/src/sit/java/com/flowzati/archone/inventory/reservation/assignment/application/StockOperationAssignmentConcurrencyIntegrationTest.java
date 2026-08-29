package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.repo.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
import com.flowzati.archone.inventory.allocation.infrastructure.repo.jdbc.JdbcStockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.infrastructure.repo.jdbc.JdbcStockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockMoveRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockOperationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockOperationTypeRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockMovePersistenceAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockOperationPersistenceAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockOperationTypePersistenceAdapter;
import com.flowzati.archone.inventory.position.infrastructure.repo.JpaStockQuantRepository;
import com.flowzati.archone.inventory.position.infrastructure.repo.StockQuantStoreImpl;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.reservation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.reservation.application.service.StockOperationAssignmentResultFactory;
import com.flowzati.archone.inventory.reservation.infrastructure.messaging.StockOperationAssignmentPublisherAdapter;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.StockMoveLineStoreImpl;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.jpa.JpaStockMoveLineRepository;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    StockOperationPersistenceAdapter.class,
    StockMovePersistenceAdapter.class,
    StockMoveLineStoreImpl.class,
    StockQuantStoreImpl.class,
    JdbcStockAllocationSupplyStore.class,
    StockOperationTypePersistenceAdapter.class,
    JdbcStockOperationAssignmentCandidateStore.class,
    StockAllocationCommitter.class,
    StockOperationAssignmentResultFactory.class,
    StockOperationAssignmentPublisherAdapter.class,
    StockOperationAssignmentConcurrencyIntegrationTest.RepositoryConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Assign operation PostgreSQL concurrency")
class StockOperationAssignmentConcurrencyIntegrationTest {

    private static final UUID EARLIER_OPERATION = uuid(1);
    private static final UUID EARLIER_MOVE = uuid(11);
    private static final UUID LATER_OPERATION = uuid(2);
    private static final UUID LATER_MOVE = uuid(12);
    private static final UUID QUANT_ID = uuid(21);
    private static final Instant EARLIER_TIME = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant LATER_TIME = EARLIER_TIME.plusSeconds(1);
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    @Autowired
    private StockAllocationCommitter allocationCommitter;

    @Autowired
    private StockOperationStore stockOperationStore;

    @Autowired
    private StockMoveStore stockMoveStore;

    @Autowired
    private StockAllocationSupplyStore stockAllocationSupplyStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private IntegrationEventPublisher eventPublisher;

    @BeforeEach
    void prepareDatabase() {
        clearScenario();
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-HOT");
    }

    @AfterEach
    void cleanDatabase() {
        clearScenario();
    }

    @Test
    @DisplayName("duplicate attempts on one operation make one transition and reconstruct the loser")
    void assignsOneOperationExactlyOnce() throws Exception {
        seedGroup(EARLIER_OPERATION, EARLIER_MOVE, uuid(51), EARLIER_TIME);
        seedQuant(3);
        StockAllocationProposal proposal = proposalFor(EARLIER_OPERATION);

        List<AttemptResult> attempts = runTogether(
                () -> allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT),
                () -> allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.failure()).isNull();
            assertThat(attempt.result()).isNotNull();
            assertThat(attempt.result().stockOperationId()).isEqualTo(EARLIER_OPERATION);
            assertThat(attempt.result().assignedAt()).isEqualTo(ASSIGNED_AT);
        });
        assertAssignedExactlyOnce(EARLIER_OPERATION, 3);
        verify(eventPublisher, times(1)).publish(org.mockito.ArgumentMatchers.any(IntegrationEventPublication.class));
    }

    @Test
    @DisplayName("two hot-SKU operations preserve FIFO and only one can reserve scarce stock")
    void givesScarceHotSkuStockOnlyToTheEarlierOperation() throws Exception {
        seedGroup(EARLIER_OPERATION, EARLIER_MOVE, uuid(51), EARLIER_TIME);
        seedGroup(LATER_OPERATION, LATER_MOVE, uuid(52), LATER_TIME);
        seedQuant(3);
        StockAllocationProposal earlierProposal = proposalFor(EARLIER_OPERATION);
        StockAllocationProposal laterProposal = proposalFor(LATER_OPERATION);

        List<AttemptResult> attempts = runTogether(
                () -> allocationCommitter.commit(earlierProposal, TODAY, ASSIGNED_AT),
                () -> allocationCommitter.commit(laterProposal, TODAY, ASSIGNED_AT));

        assertThat(attempts.getFirst().failure()).isNull();
        assertThat(attempts.getFirst().result().stockOperationId()).isEqualTo(EARLIER_OPERATION);
        assertThat(attempts.get(1).failure()).isNotNull();
        assertAssignedExactlyOnce(EARLIER_OPERATION, 3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, LATER_OPERATION))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM stock_moves WHERE id = ?", String.class, LATER_MOVE))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines WHERE move_id = ?", Integer.class, LATER_MOVE))
                .isZero();
        verify(eventPublisher, times(1)).publish(org.mockito.ArgumentMatchers.any(IntegrationEventPublication.class));
    }

    private StockAllocationProposal proposalFor(UUID stockOperationId) {
        var operation = stockOperationStore.findById(stockOperationId).orElseThrow();
        var moves = stockMoveStore.findOrderedByStockOperationId(stockOperationId);
        var demand = StockOperationDemandFactory.from(operation, moves);
        var supply = stockAllocationSupplyStore.findBySku(
                OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, demand.skuCodes(), TODAY);
        return new MovementAssignmentPlanner().plan(demand, supply);
    }

    private void seedGroup(UUID stockOperationId, UUID moveId, UUID sourceLineId, Instant enqueuedAt) {
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.update(
                    """
            INSERT INTO stock_operations
                (id, stock_operation_type_id, direction, owner_id,
                 source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
                 from_location_id, to_location_id, dispatch_by, release_priority, state, version)
            VALUES (?, ?, 'OUTBOUND', ?, 'ORDER', ?, 'PRIMARY', 'SHIP_COMPLETE', ?, ?, ?, ?, 50, 'CONFIRMED', 0)
            """,
                    stockOperationId,
                    MovementFixtures.OUTBOUND_TYPE_ID,
                    OrderFixtures.OWNER_ID,
                    uuid(stockOperationId.equals(EARLIER_OPERATION) ? 61 : 62).toString(),
                    Timestamp.from(enqueuedAt),
                    OrderFixtures.LOCATION_ID,
                    MovementFixtures.CUSTOMERS_LOCATION_ID,
                    Timestamp.from(enqueuedAt.plusSeconds(3600)));
            jdbcTemplate.update(
                    """
            INSERT INTO stock_moves
                (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
                 source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
            VALUES (?, ?, ?, 'SKU-HOT', ?, ?, ?, 1, 3, 'CONFIRMED', ?, NULL, 0)
            """,
                    moveId,
                    stockOperationId,
                    OrderFixtures.OWNER_ID,
                    OrderFixtures.LOCATION_ID,
                    MovementFixtures.CUSTOMERS_LOCATION_ID,
                    sourceLineId.toString(),
                    Timestamp.from(enqueuedAt));
        });
    }

    private void seedQuant(int quantity) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, 'SKU-HOT', ?, ?, ?, 0, 0)
        """,
                QUANT_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                Date.valueOf("2026-08-01"),
                Date.valueOf("2026-12-31"),
                quantity);
    }

    private void assertAssignedExactlyOnce(UUID stockOperationId, int quantity) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, stockOperationId))
                .isEqualTo("ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        stockOperationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reserved_quantity FROM stock_pools WHERE id = ?", Integer.class, QUANT_ID))
                .isEqualTo(quantity);
    }

    private List<AttemptResult> runTogether(AssignmentCall first, AssignmentCall second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptResult> firstFuture = executor.submit(() -> invokeTogether(first, ready, start));
            Future<AttemptResult> secondFuture = executor.submit(() -> invokeTogether(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static AttemptResult invokeTogether(AssignmentCall call, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return new AttemptResult(null, new IllegalStateException("Concurrent assignment start timed out"));
        }
        StockOperationAssignmentResult[] result = new StockOperationAssignmentResult[1];
        Throwable failure = catchThrowable(() -> result[0] = call.execute());
        return new AttemptResult(result[0], failure);
    }

    private void clearScenario() {
        jdbcTemplate.execute("DELETE FROM event_outbox");
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.execute("DELETE FROM stock_move_lines");
            jdbcTemplate.execute("DELETE FROM stock_moves");
            jdbcTemplate.execute("DELETE FROM stock_operations");
            jdbcTemplate.execute("DELETE FROM stock_pools");
        });
    }

    private record AttemptResult(StockOperationAssignmentResult result, Throwable failure) {}

    @FunctionalInterface
    private interface AssignmentCall {
        StockOperationAssignmentResult execute();
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(
            basePackageClasses = {
                JpaStockOperationRepository.class,
                JpaStockMoveRepository.class,
                JpaStockMoveLineRepository.class,
                JpaStockQuantRepository.class,
                JpaStockOperationTypeRepository.class
            })
    static class RepositoryConfiguration {}
}
