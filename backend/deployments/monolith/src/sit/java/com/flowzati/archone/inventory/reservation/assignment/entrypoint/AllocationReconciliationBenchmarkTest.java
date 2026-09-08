package com.flowzati.archone.inventory.reservation.assignment.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.allocation.application.usecase.ReconcileStockOperationBacklogUsecase;
import com.flowzati.archone.testsupport.InProcessMessagingTestConfiguration;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.SitDatabase;
import java.util.stream.IntStream;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Opt-in, serial benchmark; setup/validation excluded, real commit transactions and outbox writes included. */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = {"spring.kafka.listener.auto-startup=false", "logging.level.root=WARN"},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({AllocationReconciliationBenchmarkTest.Configuration.class, InProcessMessagingTestConfiguration.class})
@EnabledIfEnvironmentVariable(named = "ARCHONE_ALLOCATION_BENCHMARK", matches = "true")
class AllocationReconciliationBenchmarkTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockOperationAssignmentBacklogStore backlog;

    @Autowired
    private StockOperationAssignmentCoordinator coordinator;

    @Autowired
    private BusinessClock clock;

    @Autowired
    private Attempts attempts;

    @Test
    void measureFullSweep() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        seed(100, "HOT");
        run(100, "HOT", 0);
        int size = Integer.parseInt(System.getenv().getOrDefault("ARCHONE_ALLOCATION_BENCHMARK_SIZE", "10000"));
        for (String scenario : new String[] {"DISTRIBUTED", "HOT", "SHORTAGE"}) {
            seed(size, scenario);
            run(size, scenario, 1);
        }
    }

    private void run(int size, String scenario, int repeat) {
        var usecase = new ReconcileStockOperationBacklogUsecase(backlog, coordinator, clock);
        attempts.count = 0;
        attempts.failures = 0;
        jdbc.execute("SELECT pg_stat_statements_reset()");
        long start = System.nanoTime();
        usecase.execute();
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        var sql = jdbc.queryForMap("""
                SELECT COALESCE(sum(calls), 0) calls, COALESCE(sum(total_exec_time), 0) ms
                FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                AND query NOT LIKE '%pg_stat_statements%'
                """);
        long assigned =
                jdbc.queryForObject("SELECT count(*) FROM stock_operations WHERE state = 'ASSIGNED'", Long.class);
        long reserved = jdbc.queryForObject("SELECT COALESCE(sum(reserved_quantity), 0) FROM stock_pools", Long.class);
        long lines = jdbc.queryForObject("SELECT count(*) FROM stock_move_lines", Long.class);
        assertThat(attempts.failures).isZero();
        assertThat(reserved).isEqualTo(assigned);
        assertThat(lines).isEqualTo(assigned);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM stock_pools WHERE reserved_quantity > on_hand_quantity", Long.class))
                .isZero();
        if (scenario.equals("SHORTAGE")) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM stock_moves WHERE demand_quantity = 2 AND state = 'ASSIGNED'",
                            Long.class))
                    .isZero();
        }
        assertThat(assigned).isEqualTo(scenario.equals("SHORTAGE") ? size / 2 : size);
        System.out.printf(
                java.util.Locale.ROOT,
                "SWEEP,%d,%s,%d,%.3f,%d,%d,%s,%s%n",
                size,
                scenario,
                repeat,
                elapsedMs,
                attempts.count,
                assigned,
                sql.get("calls"),
                sql.get("ms"));
        if (repeat > 0) {
            jdbc.query(
                    """
                    SELECT calls, round(total_exec_time::numeric, 2), left(regexp_replace(query, '\\s+', ' ', 'g'), 180)
                    FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%'
                    ORDER BY total_exec_time DESC LIMIT 5
                    """,
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> System.out.printf(
                            "BENCH_SQL,%s,%s,%s%n", row.getObject(1), row.getObject(2), row.getString(3)));
        }
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private void seed(int size, String scenario) {
        transactionTemplate.executeWithoutResult(ignored -> seedInTransaction(size, scenario));
    }

    private void seedInTransaction(int size, String scenario) {
        SitDatabase.clear(jdbc);
        int queues = scenario.equals("HOT") ? 1 : size;
        String[] skus = IntStream.rangeClosed(1, queues)
                .mapToObj(i -> "BENCH-" + String.format("%05d", i))
                .toArray(String[]::new);
        OrderFixtures.seedCatalog(jdbc, OrderFixtures.OWNER_ID, skus);
        // Fixtures are confirmed one-line operations, avoiding ordering/workflow setup in the measurement.
        jdbc.update(
                """
                INSERT INTO stock_operations
                (id, stock_operation_type_id, direction, owner_id, source_type, source_id,
                 allocation_unit_key, policy_code, enqueued_at, from_location_id, to_location_id,
                 dispatch_by, release_priority, state, version)
                SELECT md5('op' || i)::uuid, ?, 'OUTBOUND', ?, 'ORDER', md5('order' || i)::uuid::text,
                 'PRIMARY', 'SHIP_COMPLETE', now() + i * interval '1 millisecond', ?, ?,
                 now() + interval '1 day' + i * interval '1 millisecond', 50, 'CONFIRMED', 0
                FROM generate_series(1, ?) i
                """,
                MovementFixtures.OUTBOUND_TYPE_ID,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                size);
        jdbc.update(
                """
                INSERT INTO stock_moves
                (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
                 source_line_id, line_sequence, demand_quantity, state, created_at, version)
                SELECT md5('move' || i)::uuid, md5('op' || i)::uuid, ?,
                 'BENCH-' || lpad((CASE WHEN ? THEN 1 ELSE i END)::text, 5, '0'), ?, ?,
                 md5('orderline' || i)::uuid::text, 1, CASE WHEN ? AND i <= ? THEN 2 ELSE 1 END, 'CONFIRMED', now(), 0
                FROM generate_series(1, ?) i
                """,
                OrderFixtures.OWNER_ID,
                scenario.equals("HOT"),
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                scenario.equals("SHORTAGE"),
                size / 2,
                size);
        jdbc.update("""
                INSERT INTO stock_pools
                (id, owner_id, location_id, sku_code, in_date, expiry_date, on_hand_quantity, reserved_quantity, version)
                SELECT md5('quant' || i)::uuid, ?, ?, 'BENCH-' || lpad(i::text, 5, '0'),
                DATE '2020-01-01', DATE '2099-12-31', ?, 0, 0 FROM generate_series(1, ?) i
                """, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, scenario.equals("HOT") ? size : 1, queues);
        jdbc.execute("ANALYZE");
    }

    @Aspect
    static class Attempts {
        long count;
        long failures;

        @Around(
                "execution(* com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator.tryAssignNext(..))")
        public Object count(ProceedingJoinPoint call) throws Throwable {
            count++;
            try {
                return call.proceed();
            } catch (Throwable failure) {
                failures++;
                throw failure;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine")
                    .withCommand(
                            "postgres",
                            "-c",
                            "shared_preload_libraries=pg_stat_statements",
                            "-c",
                            "pg_stat_statements.track=top");
        }

        @Bean
        Attempts attempts() {
            return new Attempts();
        }
    }
}
