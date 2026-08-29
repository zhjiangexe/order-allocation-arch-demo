package com.flowzati.archone.inventory.movement.registration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
import com.flowzati.archone.inventory.allocation.infrastructure.repo.jdbc.JdbcStockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.infrastructure.repo.jdbc.JdbcStockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.movement.application.SourceMovementConflictException;
import com.flowzati.archone.inventory.movement.application.service.StockOperationRegistrar;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.domain.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockMoveRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockOperationCancellationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockOperationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JpaStockOperationTypeRepository;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockMovePersistenceAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockOperationCancellationPersistenceAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockOperationPersistenceAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.repo.StockOperationTypePersistenceAdapter;
import com.flowzati.archone.inventory.position.infrastructure.repo.JpaStockQuantRepository;
import com.flowzati.archone.inventory.position.infrastructure.repo.StockQuantStoreImpl;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.reservation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.reservation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.reservation.application.service.StockOperationAssignmentResultFactory;
import com.flowzati.archone.inventory.reservation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.reservation.infrastructure.messaging.StockOperationAssignmentPublisherAdapter;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.OrderStockMovementStoreImpl;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.StockMoveLineStoreImpl;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.jpa.JpaOrderAllocationSourceRepository;
import com.flowzati.archone.inventory.reservation.infrastructure.repo.jpa.JpaStockMoveLineRepository;
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
    StockOperationPersistenceAdapter.class,
    StockMovePersistenceAdapter.class,
    StockMoveLineStoreImpl.class,
    StockQuantStoreImpl.class,
    StockOperationTypePersistenceAdapter.class,
    OrderStockMovementStoreImpl.class,
    StockOperationRegistrar.class,
    JdbcStockOperationAssignmentCandidateStore.class,
    JdbcStockAllocationSupplyStore.class,
    MovementAssignmentPlanner.class,
    StockAllocationCommitter.class,
    StockOperationAssignmentResultFactory.class,
    StockOperationAssignmentPublisherAdapter.class,
    StockOperationAssignmentCoordinator.class,
    AllocateOrderUsecase.class,
    StockOperationCancellationPersistenceAdapter.class,
    StockMovementRegistrationPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("Stock movement registration PostgreSQL transaction")
class StockMovementRegistrationPersistenceIntegrationTest {

    private static final UUID ORDER_ID = uuid(1);
    private static final UUID LINE_A_ID = uuid(11);
    private static final UUID LINE_B_ID = uuid(12);
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-27T01:00:00.123456Z");
    private static final Instant DISPATCH_BY = Instant.parse("2026-08-28T01:00:00.987654Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    @Autowired
    private OrderStockMovementStoreImpl orderSource;

    @Autowired
    private StockOperationRegistrar registrar;

    @Autowired
    private AllocateOrderUsecase allocateOrder;

    @Autowired
    private StockOperationCancellationPersistenceAdapter stockOperationCancellationPersistenceAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private BusinessClock appClock;

    @MockitoBean
    private IntegrationEventPublisher eventPublisher;

    @BeforeEach
    void seedOrderSource() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A", "SKU-B");
        jdbcTemplate.update(
                """
        INSERT INTO orders
            (id, owner_id, external_order_no, ship_to_zone, ship_to_address,
             promised_delivery_date, dispatch_by, release_priority, facility_id,
             status, received_at, version)
        VALUES (?, ?, 'EXT-MOVEMENT-1', '100', 'Movement test', ?, ?, 50, ?, 'PENDING', ?, 0)
        """,
                ORDER_ID,
                OrderFixtures.OWNER_ID,
                Date.valueOf("2026-08-29"),
                Timestamp.from(DISPATCH_BY),
                OrderFixtures.FACILITY_ID,
                Timestamp.from(RECEIVED_AT));
        insertOrderLine(LINE_B_ID, 1, "SKU-B", 2);
        insertOrderLine(LINE_A_ID, 2, "SKU-A", 3);
        when(appClock.today()).thenReturn(TODAY);
        when(appClock.instant()).thenReturn(RECEIVED_AT.plusSeconds(1));
        entityManager.clear();
    }

    @Test
    @DisplayName("order adapter normalizes one source unit and registrar replays equal immutable content")
    void normalizesRegistersAndReplaysTheCanonicalMovementGroup() {
        var command = orderSource.find(ORDER_ID).orElseThrow();

        assertThat(command.source()).isEqualTo(StockOperationSource.primaryOrder(ORDER_ID.toString()));
        assertThat(command.assignmentPolicy()).isEqualTo(MovementAssignmentPolicy.SHIP_COMPLETE);
        assertThat(command.fromLocationId()).isEqualTo(OrderFixtures.LOCATION_ID);
        assertThat(command.toLocationId()).isEqualTo(MovementFixtures.CUSTOMERS_LOCATION_ID);
        assertThat(command.lines())
                .extracting(line -> line.sourceLineId(), line -> line.skuCode(), line -> line.quantity())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LINE_A_ID.toString(), "SKU-A", 3),
                        org.assertj.core.groups.Tuple.tuple(LINE_B_ID.toString(), "SKU-B", 2));

        var created = registrar.register(command);
        entityManager.flush();
        entityManager.clear();
        var replayed = registrar.register(orderSource.find(ORDER_ID).orElseThrow());
        entityManager.flush();

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.operation().id()).isEqualTo(created.operation().id());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_operations WHERE source_type = 'ORDER' AND source_id = ?",
                        Integer.class,
                        ORDER_ID.toString()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT source_line_id FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        String.class,
                        created.operation().id()))
                .containsExactly(LINE_A_ID.toString(), LINE_B_ID.toString());
    }

    @Test
    @DisplayName("same source identity with changed content is rejected without partial movement writes")
    void rejectsSourceContentDriftWithoutPartialWrites() {
        var created = registrar.register(orderSource.find(ORDER_ID).orElseThrow());
        entityManager.flush();
        jdbcTemplate.update("UPDATE order_lines SET quantity = 9 WHERE id = ?", LINE_A_ID);
        entityManager.clear();

        assertThatThrownBy(() -> registrar.register(orderSource.find(ORDER_ID).orElseThrow()))
                .isInstanceOf(SourceMovementConflictException.class);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_moves WHERE stock_operation_id = ?",
                        Integer.class,
                        created.operation().id()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT SUM(demand_quantity) FROM stock_moves WHERE stock_operation_id = ?",
                        Integer.class,
                        created.operation().id()))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("initial allocation persists confirmed movement intent when supply is insufficient")
    void keepsConfirmedMovementsWhenSupplyIsInsufficient() {
        allocateOrder.execute(new AllocateOrderCommand(ORDER_ID));
        entityManager.flush();

        UUID stockOperationId = jdbcTemplate.queryForObject(
                "SELECT id FROM stock_operations WHERE source_type = 'ORDER' AND source_id = ?",
                UUID.class,
                ORDER_ID.toString());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_operations WHERE id = ?", String.class, stockOperationId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT state FROM stock_moves WHERE stock_operation_id = ? ORDER BY line_sequence",
                        String.class,
                        stockOperationId))
                .containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_move_lines line "
                                + "JOIN stock_moves move ON move.id = line.move_id WHERE move.stock_operation_id = ?",
                        Integer.class,
                        stockOperationId))
                .isZero();
    }

    @Test
    @DisplayName("operation cancellation checkpoint round-trips by operation and operation identity")
    void persistsPickingCancellationCheckpoint() {
        UUID stockOperationId = registrar
                .register(orderSource.find(ORDER_ID).orElseThrow())
                .operation()
                .id();
        UUID operationId = uuid(30);
        StockOperationCancellation operation =
                StockOperationCancellation.start(stockOperationId, operationId, RECEIVED_AT.plusSeconds(2));
        operation.confirmExternally(RECEIVED_AT.plusSeconds(3));

        stockOperationCancellationPersistenceAdapter.save(operation);
        entityManager.flush();
        entityManager.clear();

        StockOperationCancellation restored = stockOperationCancellationPersistenceAdapter
                .find(stockOperationId, operationId)
                .orElseThrow();
        assertThat(restored.stockOperationId()).isEqualTo(stockOperationId);
        assertThat(restored.state()).isEqualTo(StockOperationCancellationState.EXTERNAL_CONFIRMED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_operation_cancellations "
                                + "WHERE stock_operation_id = ? AND cancellation_operation_id = ?",
                        Integer.class,
                        stockOperationId,
                        operationId))
                .isEqualTo(1);
    }

    private void insertOrderLine(UUID lineId, int lineNumber, String skuCode, int quantity) {
        jdbcTemplate.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity)
        VALUES (?, ?, ?, ?, ?, ?)
        """, lineId, ORDER_ID, lineNumber, OrderFixtures.OWNER_ID, skuCode, quantity);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(
            basePackageClasses = {
                JpaOrderAllocationSourceRepository.class,
                JpaStockOperationRepository.class,
                JpaStockMoveRepository.class,
                JpaStockMoveLineRepository.class,
                JpaStockQuantRepository.class,
                JpaStockOperationTypeRepository.class,
                JpaStockOperationCancellationRepository.class
            })
    static class RepositoryConfiguration {}
}
