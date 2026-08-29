package com.flowzati.archone.inventory.reservation.release.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteStockOperationUsecase;
import com.flowzati.archone.inventory.reservation.application.usecase.ReleaseStockOperationUsecase;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class AllocationReleaseConsumeConcurrencyIntegrationTest {

    private static final UUID STOCK_OPERATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000801");
    private static final UUID MOVE_ID = UUID.fromString("00000000-0000-7000-8000-000000000802");
    private static final UUID QUANT_ID = UUID.fromString("00000000-0000-7000-8000-000000000803");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T02:00:00Z");

    @Autowired
    private ReleaseStockOperationUsecase releaseStockOperationUsecase;

    @Autowired
    private CompleteStockOperationUsecase completeStockOperationUsecase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedAssignedPicking() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) {
                connection.setAutoCommit(false);
            }
            JdbcTemplate transaction = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                seedAssignedPicking(transaction);
                if (ownsTransaction) {
                    connection.commit();
                }
            } catch (RuntimeException | java.sql.SQLException failure) {
                if (ownsTransaction) {
                    connection.rollback();
                }
                throw failure;
            } finally {
                if (ownsTransaction) {
                    connection.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    private void seedAssignedPicking(JdbcTemplate transaction) {
        OrderFixtures.seedCatalog(transaction, OrderFixtures.OWNER_ID, "SKU-TERMINAL-RACE");
        transaction.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, 'SKU-TERMINAL-RACE', ?, ?, 10, 3, 0)
        """,
                QUANT_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                Date.valueOf(LocalDate.parse("2026-08-01")),
                Date.valueOf(LocalDate.parse("2026-12-31")));
        transaction.update(
                """
        INSERT INTO stock_operations
            (id, stock_operation_type_id, direction, owner_id,
             source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
             from_location_id, to_location_id, dispatch_by, release_priority, state, version)
        VALUES (?, ?, 'OUTBOUND', ?, 'ORDER', ?, 'PRIMARY', 'SHIP_COMPLETE', ?, ?, ?, ?, 50, 'ASSIGNED', 0)
        """,
                STOCK_OPERATION_ID,
                MovementFixtures.OUTBOUND_TYPE_ID,
                OrderFixtures.OWNER_ID,
                UUID.randomUUID().toString(),
                Timestamp.from(ASSIGNED_AT.minusSeconds(60)),
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(ASSIGNED_AT.plusSeconds(3600)));
        transaction.update(
                """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, 'SKU-TERMINAL-RACE', ?, ?, 'LINE-1', 1, 3, 'ASSIGNED', ?, ?, 0)
        """,
                MOVE_ID,
                STOCK_OPERATION_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(ASSIGNED_AT.minusSeconds(60)),
                Timestamp.from(ASSIGNED_AT));
        transaction.update(
                "INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity) VALUES (?, ?, ?, 3)",
                UUID.randomUUID(),
                MOVE_ID,
                QUANT_ID);
    }

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    @Test
    void allowsExactlyOneTerminalTransitionWithoutStockOrExecutionDrift() throws Exception {
        Instant terminalAt = ASSIGNED_AT.plusSeconds(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> release = executor.submit(() ->
                    race(ready, start, () -> releaseStockOperationUsecase.execute(STOCK_OPERATION_ID, terminalAt)));
            Future<Object> complete = executor.submit(() ->
                    race(ready, start, () -> completeStockOperationUsecase.execute(STOCK_OPERATION_ID, terminalAt)));

            ready.await();
            start.countDown();
            assertThat(List.of(release.get(), complete.get()).stream().filter(Boolean.TRUE::equals))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        int onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand_quantity FROM stock_pools WHERE id = ?", Integer.class, QUANT_ID);
        int reserved = jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM stock_pools WHERE id = ?", Integer.class, QUANT_ID);
        String pickingState = jdbcTemplate.queryForObject(
                "SELECT state FROM stock_operations WHERE id = ?", String.class, STOCK_OPERATION_ID);
        String moveState =
                jdbcTemplate.queryForObject("SELECT state FROM stock_moves WHERE id = ?", String.class, MOVE_ID);
        int movementLineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_move_lines WHERE move_id = ?", Integer.class, MOVE_ID);

        assertThat(reserved).isZero();
        if (pickingState.equals("DONE")) {
            assertThat(onHand).isEqualTo(7);
            assertThat(moveState).isEqualTo("DONE");
            assertThat(movementLineCount).isOne();
        } else {
            assertThat(pickingState).isEqualTo("CONFIRMED");
            assertThat(onHand).isEqualTo(10);
            assertThat(moveState).isEqualTo("CONFIRMED");
            assertThat(movementLineCount).isZero();
        }
    }

    private static Object race(CountDownLatch ready, CountDownLatch start, ThrowingSupplier operation) {
        ready.countDown();
        try {
            start.await();
            return operation.get();
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get();
    }
}
