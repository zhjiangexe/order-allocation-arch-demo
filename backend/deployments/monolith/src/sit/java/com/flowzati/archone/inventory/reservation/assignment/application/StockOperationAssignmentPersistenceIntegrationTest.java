package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.store.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.movement.infrastructure.messaging.StockOperationCompletedIntegrationEventAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.messaging.StockOperationLifecycleChangedIntegrationEventAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockMoveRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationTypeRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockMoveStoreImpl;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockOperationStoreImpl;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockOperationTypeStoreImpl;
import com.flowzati.archone.inventory.position.infrastructure.persistence.jpa.repository.JpaStockQuantRepository;
import com.flowzati.archone.inventory.position.infrastructure.persistence.jpa.store.StockQuantStoreImpl;
import com.flowzati.archone.inventory.reservation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.reservation.application.service.StockOperationAssignmentResultFactory;
import com.flowzati.archone.inventory.reservation.application.usecase.ReleaseStockOperationUsecase;
import com.flowzati.archone.inventory.reservation.infrastructure.messaging.StockOperationAssignedIntegrationEventAdapter;
import com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.repository.JpaStockMoveLineRepository;
import com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.store.StockMoveLineStoreImpl;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    StockOperationStoreImpl.class,
    StockMoveStoreImpl.class,
    StockMoveLineStoreImpl.class,
    StockQuantStoreImpl.class,
    JdbcStockAllocationSupplyStore.class,
    StockOperationTypeStoreImpl.class,
    JdbcStockOperationAssignmentCandidateStore.class,
    StockAllocationCommitter.class,
    StockOperationAssignmentResultFactory.class,
    StockOperationAssignedIntegrationEventAdapter.class,
    StockOperationLifecycleChangedIntegrationEventAdapter.class,
    StockOperationCompletedIntegrationEventAdapter.class,
    ReleaseStockOperationUsecase.class,
    CompleteOutboundMovementsUsecase.class,
    StockOperationAssignmentPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("Assign operation PostgreSQL transaction")
class StockOperationAssignmentPersistenceIntegrationTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID MOVE_1 = uuid(11);
    private static final UUID MOVE_2 = uuid(12);
    private static final UUID QUANT_1 = uuid(21);
    private static final UUID QUANT_2 = uuid(22);
    private static final UUID ORDER_ID = uuid(50);
    private static final UUID SHIPMENT_ID = uuid(60);
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    @Autowired
    private StockAllocationCommitter allocationCommitter;

    @Autowired
    private ReleaseStockOperationUsecase releaseOperation;

    @Autowired
    private CompleteOutboundMovementsUsecase completeOperation;

    @Autowired
    private StockOperationStore stockOperationStore;

    @Autowired
    private StockMoveStore stockMoveStore;

    @Autowired
    private StockAllocationSupplyStore stockAllocationSupplyStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private IntegrationEventPublisher eventPublisher;

    @BeforeEach
    void seedConfirmedGroupAndStock() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A");
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
                ORDER_ID.toString(),
                Timestamp.from(ENQUEUED_AT),
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(ENQUEUED_AT.plusSeconds(3600)));
        insertMove(MOVE_1, uuid(51), 1, 2);
        insertMove(MOVE_2, uuid(52), 2, 4);
        insertQuant(QUANT_1, LocalDate.parse("2026-09-01"), 3);
        insertQuant(QUANT_2, LocalDate.parse("2026-10-01"), 5);
        entityManager.clear();
    }

    @Test
    @DisplayName("assigns the existing group once and reconstructs a retry from retained move lines")
    void assignsAndReconstructsWithoutDoubleReservation() {
        var operation = stockOperationStore.findById(STOCK_OPERATION_ID).orElseThrow();
        var moves = stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID);
        var demand = StockOperationDemandFactory.from(operation, moves);
        var supply = stockAllocationSupplyStore.findBySku(
                OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, demand.skuCodes(), TODAY);
        var proposal = new MovementAssignmentPlanner().plan(demand, supply);

        var committed = allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT);
        entityManager.flush();
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(committed.moves())
                .extracting(move -> move.moveId(), move -> move.quantity())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_1, 2), org.assertj.core.groups.Tuple.tuple(MOVE_2, 4));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, STOCK_OPERATION_ID))
                .isEqualTo("ASSIGNED");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT state FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        String.class,
                        STOCK_OPERATION_ID))
                .containsExactly("ASSIGNED", "ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT SUM(quantity) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        STOCK_OPERATION_ID))
                .isEqualTo(6);
        assertThat(reservedQuantity()).isEqualTo(6);

        var replay = allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT.plusSeconds(30));
        entityManager.flush();

        assertThat(replay).isEqualTo(committed);
        assertThat(reservedQuantity()).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        STOCK_OPERATION_ID))
                .isEqualTo(3);
        verify(eventPublisher, times(1)).publish(org.mockito.ArgumentMatchers.any(IntegrationEventPublication.class));
    }

    @Test
    @DisplayName("release removes current detail and returns the same operation and moves to confirmed")
    void releasesTheAssignedGroupWithoutReplacingMovementIdentity() {
        var operation = stockOperationStore.findById(STOCK_OPERATION_ID).orElseThrow();
        var moves = stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID);
        var demand = StockOperationDemandFactory.from(operation, moves);
        var supply = stockAllocationSupplyStore.findBySku(
                OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, demand.skuCodes(), TODAY);
        var proposal = new MovementAssignmentPlanner().plan(demand, supply);
        allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT);
        entityManager.flush();
        entityManager.clear();

        assertThat(releaseOperation.execute(STOCK_OPERATION_ID, ASSIGNED_AT.plusSeconds(60)))
                .isTrue();
        entityManager.flush();
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, STOCK_OPERATION_ID))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        UUID.class,
                        STOCK_OPERATION_ID))
                .containsExactly(MOVE_1, MOVE_2);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT state FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        String.class,
                        STOCK_OPERATION_ID))
                .containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        STOCK_OPERATION_ID))
                .isZero();
        assertThat(reservedQuantity()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT SUM(on_hand_quantity) FROM stock_pools WHERE id IN (?, ?)",
                        Integer.class,
                        QUANT_1,
                        QUANT_2))
                .isEqualTo(8);
    }

    @Test
    @DisplayName("completion consumes physical stock and retains exact move-line execution evidence")
    void completesTheAssignedGroupAndRetainsMoveLines() {
        var operation = stockOperationStore.findById(STOCK_OPERATION_ID).orElseThrow();
        var moves = stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID);
        var demand = StockOperationDemandFactory.from(operation, moves);
        var supply = stockAllocationSupplyStore.findBySku(
                OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, demand.skuCodes(), TODAY);
        var proposal = new MovementAssignmentPlanner().plan(demand, supply);
        allocationCommitter.commit(proposal, TODAY, ASSIGNED_AT);
        entityManager.flush();
        entityManager.clear();

        assertThat(completeOperation.execute(new CompleteOutboundMovementsCommand(
                        ORDER_ID,
                        SHIPMENT_ID,
                        STOCK_OPERATION_ID,
                        java.util.List.of(MOVE_1, MOVE_2),
                        ASSIGNED_AT.plusSeconds(60))))
                .isTrue();
        entityManager.flush();
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, STOCK_OPERATION_ID))
                .isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT state FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        String.class,
                        STOCK_OPERATION_ID))
                .containsExactly("DONE", "DONE");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        STOCK_OPERATION_ID))
                .isEqualTo(3);
        assertThat(reservedQuantity()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT SUM(on_hand_quantity) FROM stock_pools WHERE id IN (?, ?)",
                        Integer.class,
                        QUANT_1,
                        QUANT_2))
                .isEqualTo(2);

        entityManager.clear();
        assertThat(completeOperation.execute(new CompleteOutboundMovementsCommand(
                        ORDER_ID,
                        SHIPMENT_ID,
                        STOCK_OPERATION_ID,
                        java.util.List.of(MOVE_1, MOVE_2),
                        ASSIGNED_AT.plusSeconds(120))))
                .isFalse();
    }

    private void insertMove(UUID moveId, UUID sourceLineId, int sequence, int quantity) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, ?, ?, ?, 'CONFIRMED', ?, NULL, 0)
        """,
                moveId,
                STOCK_OPERATION_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                sourceLineId.toString(),
                sequence,
                quantity,
                Timestamp.from(ENQUEUED_AT));
    }

    private void insertQuant(UUID quantId, LocalDate expiryDate, int onHand) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, ?, 0, 0)
        """,
                quantId,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                Date.valueOf("2026-08-01"),
                Date.valueOf(expiryDate),
                onHand);
    }

    private int reservedQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT SUM(reserved_quantity) FROM stock_pools WHERE id IN (?, ?)", Integer.class, QUANT_1, QUANT_2);
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
