package com.flowzati.archone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseFoundationIntegrationTest {

    private static final String ROLLBACK_PROBE_TABLE = "sr08_rollback_probe";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @BeforeEach
    void removeRollbackProbeIfPresent() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + ROLLBACK_PROBE_TABLE);
    }

    @Test
    @DisplayName("Flyway 應套用並驗證所有資料庫 migration")
    void appliesAndValidatesAllMigrations() {
        var appliedVersions = Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList();

        assertThat(appliedVersions)
                .containsExactly(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17",
                        "18");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("交易失敗時應回滾資料庫變更")
    void rollsBackDatabaseChangesWhenTransactionFails() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.execute("CREATE TABLE " + ROLLBACK_PROBE_TABLE + " (id INTEGER PRIMARY KEY)");
                    jdbcTemplate.update("INSERT INTO " + ROLLBACK_PROBE_TABLE + " (id) VALUES (1)");
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Integer tableCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = ?
        """, Integer.class, ROLLBACK_PROBE_TABLE);

        assertThat(tableCount).isZero();
    }

    @Test
    @DisplayName("V13–V15 可 backfill no-move、CONFIRMED、ASSIGNED 並建立 execution constraint")
    void backfillsRepresentativeActiveOrderExecutionIdempotently() throws Exception {
        String schema = "allocation_backfill_" + UUID.randomUUID().toString().replace("-", "");
        DataSource stagedDataSource = new DriverManagerDataSource(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure()
                    .dataSource(stagedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("12")
                    .load()
                    .migrate();

            try (Connection connection = stagedDataSource.getConnection()) {
                try {
                    connection.setSchema(schema);
                    JdbcTemplate staged = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    seedRepresentativePreBackfillData(staged);

                    Flyway.configure()
                            .dataSource(stagedDataSource)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations("classpath:db/migration")
                            .load()
                            .migrate();

                    assertRepresentativeBackfill(staged);
                    String backfillSql = new ClassPathResource("db/migration/V13__backfill_allocation_demands.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    staged.execute(backfillSql);
                    assertRepresentativeBackfill(staged);
                } finally {
                    // Hikari does not reset PostgreSQL's schema setting when a connection is returned.
                    connection.setSchema("public");
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void seedRepresentativePreBackfillData(JdbcTemplate staged) {
        UUID executionLocation = uuid("c9");
        OrderFixtures.seedCatalog(staged, OrderFixtures.OWNER_ID, "SKU-A");
        staged.update("""
        INSERT INTO stock_locations (id, facility_id, code, name, usage)
        VALUES (?, ?, 'WH-TEST/Backfill', 'Backfill execution location', 'INTERNAL')
        """, executionLocation, OrderFixtures.FACILITY_ID);

        UUID noMoveOrder = uuid("9101");
        UUID confirmedOrder = uuid("9102");
        UUID assignedOrder = uuid("9103");
        seedOrder(staged, noMoveOrder, uuid("9201"), "NO-MOVE", Instant.parse("2026-08-18T01:00:00Z"));
        seedOrder(staged, confirmedOrder, uuid("9202"), "CONFIRMED", Instant.parse("2026-08-18T01:05:00Z"));
        seedOrder(staged, assignedOrder, uuid("9203"), "ASSIGNED", Instant.parse("2026-08-18T01:15:00Z"));

        seedExecution(
                staged,
                confirmedOrder,
                uuid("9202"),
                uuid("9302"),
                uuid("9402"),
                executionLocation,
                "CONFIRMED",
                Instant.parse("2026-08-18T01:10:00Z"));
        seedExecution(
                staged,
                assignedOrder,
                uuid("9203"),
                uuid("9303"),
                uuid("9403"),
                executionLocation,
                "ASSIGNED",
                Instant.parse("2026-08-18T01:20:00Z"));
        staged.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, 10, 1)
        """,
                uuid("9503"),
                OrderFixtures.OWNER_ID,
                executionLocation,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2027-08-01"));
        staged.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        VALUES (?, ?, ?, 1)
        """, uuid("9603"), uuid("9403"), uuid("9503"));
    }

    private static void seedOrder(
            JdbcTemplate staged, UUID orderId, UUID lineId, String externalNo, Instant receivedAt) {
        staged.update(
                """
        INSERT INTO orders
            (id, owner_id, external_order_no, ship_to_zone, ship_to_address,
             promised_delivery_date, dispatch_by, release_priority, facility_id,
             status, received_at)
        VALUES (?, ?, ?, '100', 'Backfill fixture', ?, ?, 50, ?, 'PENDING', ?)
        """,
                orderId,
                OrderFixtures.OWNER_ID,
                externalNo,
                LocalDate.parse("2026-08-22"),
                Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")),
                OrderFixtures.FACILITY_ID,
                Timestamp.from(receivedAt));
        staged.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity)
        VALUES (?, ?, 1, ?, 'SKU-A', 1)
        """, lineId, orderId, OrderFixtures.OWNER_ID);
    }

    private static void seedExecution(
            JdbcTemplate staged,
            UUID orderId,
            UUID lineId,
            UUID pickingId,
            UUID moveId,
            UUID sourceLocation,
            String state,
            Instant createdAt) {
        staged.update(
                """
        INSERT INTO stock_pickings
            (id, picking_type_id, owner_id, order_id, from_location_id, to_location_id,
             dispatch_by, release_priority, state, direction)
        VALUES (?, ?, ?, ?, ?, ?, ?, 50, ?, 'OUTBOUND')
        """,
                pickingId,
                MovementFixtures.OUTBOUND_TYPE_ID,
                OrderFixtures.OWNER_ID,
                orderId,
                sourceLocation,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")),
                state);
        staged.update(
                """
        INSERT INTO stock_moves
            (id, picking_id, owner_id, sku_code, from_location_id, to_location_id,
             order_line_id, demand_quantity, state, created_at, assigned_at)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, ?, 1, ?, ?, ?)
        """,
                moveId,
                pickingId,
                OrderFixtures.OWNER_ID,
                sourceLocation,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                lineId,
                state,
                Timestamp.from(createdAt),
                "ASSIGNED".equals(state) ? Timestamp.from(createdAt) : null);
    }

    private static void assertRepresentativeBackfill(JdbcTemplate staged) {
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM allocation_demands", Integer.class))
                .isEqualTo(3);
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM allocation_demand_lines", Integer.class))
                .isEqualTo(3);
        assertThat(staged.queryForList("""
        SELECT source_id, status, location_id, enqueued_at
          FROM allocation_demands
         ORDER BY source_id
        """))
                .extracting(row -> row.get("status"))
                .containsExactly("PENDING", "PENDING", "ALLOCATED");
        assertThat(staged.queryForObject("""
        SELECT COUNT(*)
          FROM stock_moves
         WHERE allocation_demand_id IS NULL
            OR allocation_demand_line_id IS NULL
            OR source_line_id IS DISTINCT FROM order_line_id::text
        """, Integer.class)).isZero();
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class))
                .isZero();

        assertDemandSnapshot(staged, uuid("9101"), OrderFixtures.LOCATION_ID, Instant.parse("2026-08-18T01:00:00Z"));
        assertDemandSnapshot(staged, uuid("9102"), uuid("c9"), Instant.parse("2026-08-18T01:10:00Z"));
        assertDemandSnapshot(staged, uuid("9103"), uuid("c9"), Instant.parse("2026-08-18T01:20:00Z"));
    }

    private static void assertDemandSnapshot(JdbcTemplate staged, UUID orderId, UUID locationId, Instant enqueuedAt) {
        var snapshot = staged.queryForMap("""
        SELECT location_id, enqueued_at
          FROM allocation_demands
         WHERE source_type = 'ORDER'
           AND source_id = ?
           AND allocation_unit_key = 'PRIMARY'
        """, orderId.toString());
        assertThat(snapshot.get("location_id")).isEqualTo(locationId);
        assertThat(((Timestamp) snapshot.get("enqueued_at")).toInstant()).isEqualTo(enqueuedAt);
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + "0".repeat(12 - suffix.length()) + suffix);
    }
}
