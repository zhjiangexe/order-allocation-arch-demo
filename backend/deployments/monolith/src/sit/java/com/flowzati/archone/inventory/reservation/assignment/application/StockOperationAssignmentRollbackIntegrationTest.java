package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowzati.archone.inventory.allocation.application.repo.StockAllocationSupplyStore;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    StockOperationAssignmentRollbackIntegrationTest.RepositoryConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Assign operation PostgreSQL rollback")
class StockOperationAssignmentRollbackIntegrationTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID MOVE_ID = uuid(11);
    private static final UUID QUANT_ID = uuid(21);
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00Z");
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
    void seedConfirmedGroupAndStock() {
        removeFailureConstraints();
        clearScenario();
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A");
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.update(
                    """
        INSERT INTO stock_operations
            (id, stock_operation_type_id, direction, owner_id,
             source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
             from_location_id, to_location_id, dispatch_by, release_priority, state, version)
        VALUES (?, ?, 'OUTBOUND', ?, 'ORDER', ?, 'PRIMARY', 'SHIP_COMPLETE', ?, ?, ?, ?, 50, 'CONFIRMED', 0)
        """,
                    STOCK_OPERATION_ID,
                    MovementFixtures.OUTBOUND_TYPE_ID,
                    OrderFixtures.OWNER_ID,
                    uuid(50).toString(),
                    Timestamp.from(ENQUEUED_AT),
                    OrderFixtures.LOCATION_ID,
                    MovementFixtures.CUSTOMERS_LOCATION_ID,
                    Timestamp.from(ENQUEUED_AT.plusSeconds(3600)));
            jdbcTemplate.update(
                    """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, ?, 1, 3, 'CONFIRMED', ?, NULL, 0)
        """,
                    MOVE_ID,
                    STOCK_OPERATION_ID,
                    OrderFixtures.OWNER_ID,
                    OrderFixtures.LOCATION_ID,
                    MovementFixtures.CUSTOMERS_LOCATION_ID,
                    uuid(51).toString(),
                    Timestamp.from(ENQUEUED_AT));
        });
        jdbcTemplate.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, 3, 0, 0)
        """,
                QUANT_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                Date.valueOf("2026-08-01"),
                Date.valueOf("2026-12-31"));
    }

    @AfterEach
    void cleanScenario() {
        removeFailureConstraints();
        clearScenario();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(AssignmentFailure.class)
    @DisplayName("every persistence and publication failure restores the original confirmed group")
    void rollsBackEveryAssignmentWrite(AssignmentFailure failure) {
        var operation = stockOperationStore.findById(STOCK_OPERATION_ID).orElseThrow();
        var moves = stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID);
        var demand = StockOperationDemandFactory.from(operation, moves);
        var supply = stockAllocationSupplyStore.findBySku(
                OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, demand.skuCodes(), TODAY);
        var proposal = new MovementAssignmentPlanner().plan(demand, supply);
        AssignmentSnapshot before = snapshot();

        if (failure == AssignmentFailure.PUBLICATION) {
            doThrow(new IllegalStateException(failure.constraintName()))
                    .when(eventPublisher)
                    .publish(any(IntegrationEventPublication.class));
        } else {
            failure.install(jdbcTemplate);
        }

        Throwable thrown = catchThrowable(() -> allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT));

        assertThat(thrown).isNotNull();
        assertThat(rootCause(thrown).getMessage()).contains(failure.constraintName());
        assertThat(snapshot()).isEqualTo(before);
    }

    private AssignmentSnapshot snapshot() {
        return new AssignmentSnapshot(
                jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, STOCK_OPERATION_ID),
                jdbcTemplate.queryForObject("SELECT state FROM stock_moves WHERE id = ?", String.class, MOVE_ID),
                jdbcTemplate.queryForObject(
                        "SELECT reserved_quantity FROM stock_pools WHERE id = ?", Integer.class, QUANT_ID),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines WHERE move_id = ?", Integer.class, MOVE_ID),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class));
    }

    private void clearScenario() {
        jdbcTemplate.execute("DELETE FROM event_outbox");
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.execute("DELETE FROM stock_move_lines");
            jdbcTemplate.execute("DELETE FROM stock_moves");
            jdbcTemplate.execute("DELETE FROM stock_operations");
        });
        jdbcTemplate.execute("DELETE FROM stock_pools");
    }

    private void removeFailureConstraints() {
        for (AssignmentFailure failure : AssignmentFailure.values()) {
            failure.remove(jdbcTemplate);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record AssignmentSnapshot(
            String pickingState, String moveState, int reservedQuantity, int moveLineCount, int outboxCount) {}

    private enum AssignmentFailure {
        COUNTER("stock_pools", "reserved_quantity = 0", "ck_test_reject_assignment_counter"),
        MOVE_LINE("stock_move_lines", "false", "ck_test_reject_assignment_move_line"),
        MOVE_STATE("stock_moves", "state = 'CONFIRMED'", "ck_test_reject_assignment_move_state"),
        STOCK_OPERATION_STATE(
                "stock_operations", "state = 'CONFIRMED'", "ck_test_reject_assignment_stock_operation_state"),
        PUBLICATION(null, null, "test_reject_assignment_publication");

        private final String table;
        private final String predicate;
        private final String constraintName;

        AssignmentFailure(String table, String predicate, String constraintName) {
            this.table = table;
            this.predicate = predicate;
            this.constraintName = constraintName;
        }

        void install(JdbcTemplate jdbcTemplate) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName + " CHECK (" + predicate
                    + ") NOT VALID");
        }

        void remove(JdbcTemplate jdbcTemplate) {
            if (table != null) {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraintName);
            }
        }

        String constraintName() {
            return constraintName;
        }
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
