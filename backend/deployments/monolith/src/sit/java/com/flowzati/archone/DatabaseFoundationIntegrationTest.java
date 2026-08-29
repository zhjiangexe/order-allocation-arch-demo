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
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
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
                        "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("V30 只留下 canonical stock-operation schema metadata 與 deferred guards")
    void exposesOnlyCanonicalStockOperationMetadata() {
        assertThat(tableExists(jdbcTemplate, "stock_operation_types")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_operations")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_operation_cancellations")).isTrue();
        assertThat(tableExists(jdbcTemplate, "stock_picking_types")).isFalse();
        assertThat(tableExists(jdbcTemplate, "stock_pickings")).isFalse();
        assertThat(tableExists(jdbcTemplate, "stock_picking_cancellation_operations"))
                .isFalse();
        assertThat(columnExists(jdbcTemplate, "stock_operations", "stock_operation_type_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_moves", "stock_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_operation_cancellations", "stock_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "stock_operation_cancellations", "cancellation_operation_id"))
                .isTrue();
        assertThat(columnExists(jdbcTemplate, "wms_shipments", "stock_operation_id"))
                .isTrue();

        List<String> relationshipMetadata = jdbcTemplate.queryForList("""
        SELECT conname
          FROM pg_constraint constraint_metadata
          JOIN pg_class table_metadata ON table_metadata.oid = constraint_metadata.conrelid
         WHERE table_metadata.relname IN (
             'stock_operation_types', 'stock_operations', 'stock_moves',
             'stock_operation_cancellations', 'wms_shipments')
        UNION ALL
        SELECT indexname
          FROM pg_indexes
         WHERE schemaname = current_schema()
           AND tablename IN (
               'stock_operation_types', 'stock_operations', 'stock_moves',
               'stock_operation_cancellations', 'wms_shipments')
        """, String.class);
        assertThat(relationshipMetadata).noneMatch(name -> name.contains("picking"));

        assertThat(jdbcTemplate.queryForList(
                        "SELECT proname FROM pg_proc WHERE pronamespace = current_schema()::regnamespace",
                        String.class))
                .contains("assert_stock_operation", "enforce_stock_operation_group")
                .doesNotContain("assert_stock_picking_group", "enforce_stock_picking_group");
        assertThat(jdbcTemplate.queryForList("""
        SELECT trigger_name
          FROM information_schema.triggers
         WHERE trigger_schema = current_schema()
           AND event_object_table IN ('stock_operations', 'stock_moves', 'stock_move_lines')
        """, String.class))
                .contains(
                        "ck_stock_operations_move_group",
                        "ck_stock_moves_operation_group",
                        "ck_stock_move_lines_operation_group")
                .noneMatch(name -> name.contains("picking"));
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
    @DisplayName("V13–V29 可決定性轉換 pending、assigned、done、cancelled 為 operation/move 並可重跑")
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
                            .target("22")
                            .load()
                            .migrate();
                    seedLegacyCancellationOperations(staged);

                    migrateTo(stagedDataSource, schema, "29");
                    seedV29WmsRelationship(staged);
                    StockOperationUpgradeSnapshot beforeRename = v29StockOperationSnapshot(staged);

                    Flyway stagedFlyway = Flyway.configure()
                            .dataSource(stagedDataSource)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations("classpath:db/migration")
                            .load();
                    stagedFlyway.migrate();

                    assertThat(v30StockOperationSnapshot(staged)).isEqualTo(beforeRename);
                    assertRepresentativeBackfill(staged);
                    assertThat(stagedFlyway.migrate().migrationsExecuted).isZero();
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

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(CutoverMismatch.class)
    @DisplayName("V24 任一 move-centric cutover invariant mismatch 都應中止 migration")
    void abortsCommitmentCutoverOnEveryMismatch(CutoverMismatch mismatch) throws Exception {
        String schema = "allocation_validation_" + UUID.randomUUID().toString().replace("-", "");
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
                            .target("23")
                            .load()
                            .migrate();
                    mismatch.inject(staged);

                    Flyway cutover = Flyway.configure()
                            .dataSource(stagedDataSource)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations("classpath:db/migration")
                            .load();
                    assertThatThrownBy(cutover::migrate)
                            .isInstanceOf(FlywayException.class)
                            .hasStackTraceContaining(mismatch.failureMessage);
                } finally {
                    connection.setSchema("public");
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("expand、backfill、contract 三階段應只在最後移除 legacy demand model")
    void keepsLegacyDemandReadableUntilMoveCentricContractPhase() throws Exception {
        String schema = "movement_cutover_" + UUID.randomUUID().toString().replace("-", "");
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

                    migrateTo(stagedDataSource, schema, "21");
                    assertThat(tableExists(staged, "allocation_demands")).isTrue();
                    assertThat(columnExists(staged, "stock_pickings", "source_type"))
                            .isTrue();
                    assertThat(tableExists(staged, "allocations")).isFalse();

                    migrateTo(stagedDataSource, schema, "23");
                    assertThat(tableExists(staged, "allocation_demands")).isTrue();
                    assertThat(staged.queryForObject(
                                    "SELECT COUNT(*) FROM stock_pickings WHERE legacy_allocation_demand_id IS NOT NULL",
                                    Integer.class))
                            .isEqualTo(5);
                    assertThat(staged.queryForObject(
                                    "SELECT COUNT(*) FROM stock_moves WHERE line_sequence IS NOT NULL", Integer.class))
                            .isEqualTo(5);

                    migrateTo(stagedDataSource, schema, "29");
                    assertThat(tableExists(staged, "allocation_demands")).isFalse();
                    assertThat(tableExists(staged, "allocation_demand_lines")).isFalse();
                    assertThat(tableExists(staged, "allocations")).isFalse();
                    assertThat(columnExists(staged, "stock_moves", "allocation_demand_id"))
                            .isFalse();
                    assertThat(columnExists(staged, "stock_move_lines", "allocation_slice_id"))
                            .isFalse();
                } finally {
                    connection.setSchema("public");
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void migrateTo(DataSource dataSource, String schema, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static void seedLegacyCancellationOperations(JdbcTemplate staged) {
        staged.update(
                """
        INSERT INTO allocation_cancellation_operations
            (allocation_demand_id, operation_id, state, started_at, updated_at, version)
        SELECT id, ?, 'EXTERNAL_CONFIRMED', ?, ?, 0
          FROM allocation_demands
         WHERE source_id = ?
        """,
                uuid("9703"),
                Timestamp.from(Instant.parse("2026-08-18T01:21:00Z")),
                Timestamp.from(Instant.parse("2026-08-18T01:22:00Z")),
                uuid("9103").toString());
        staged.update(
                """
        INSERT INTO allocation_cancellation_operations
            (allocation_demand_id, operation_id, state, started_at, updated_at, version)
        SELECT id, ?, 'COMPLETED', ?, ?, 0
          FROM allocation_demands
         WHERE source_id = ?
        """,
                uuid("9701"),
                Timestamp.from(Instant.parse("2026-08-18T01:01:00Z")),
                Timestamp.from(Instant.parse("2026-08-18T01:02:00Z")),
                uuid("9101").toString());
    }

    private static void seedV29WmsRelationship(JdbcTemplate staged) {
        staged.update(
                """
        INSERT INTO wms_shipments
            (id, picking_id, order_id, owner_id, facility_id, created_at, dispatch_by,
             release_priority, status, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 50, 'CREATED', 7)
        """,
                uuid("9803"),
                uuid("9303"),
                uuid("9103"),
                OrderFixtures.OWNER_ID,
                OrderFixtures.FACILITY_ID,
                Timestamp.from(Instant.parse("2026-08-18T02:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")));
    }

    private static StockOperationUpgradeSnapshot v29StockOperationSnapshot(JdbcTemplate staged) {
        return stockOperationUpgradeSnapshot(staged, "stock_pickings", "picking_type_id", "picking_id");
    }

    private static StockOperationUpgradeSnapshot v30StockOperationSnapshot(JdbcTemplate staged) {
        return stockOperationUpgradeSnapshot(
                staged, "stock_operations", "stock_operation_type_id", "stock_operation_id");
    }

    private static StockOperationUpgradeSnapshot stockOperationUpgradeSnapshot(
            JdbcTemplate staged, String operationTable, String operationTypeColumn, String relationshipColumn) {
        List<OperationUpgradeRow> operations = staged.query(
                "SELECT operation.id, operation." + operationTypeColumn
                        + ", operation.source_type, operation.source_id, operation.allocation_unit_key, "
                        + "operation.state, operation.enqueued_at, operation.version, move.id AS move_id, move."
                        + relationshipColumn
                        + " AS move_operation_id FROM "
                        + operationTable
                        + " operation JOIN stock_moves move ON move."
                        + relationshipColumn
                        + " = operation.id ORDER BY operation.id, move.id",
                (rs, rowNum) -> new OperationUpgradeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject(operationTypeColumn, UUID.class),
                        rs.getString("source_type"),
                        rs.getString("source_id"),
                        rs.getString("allocation_unit_key"),
                        rs.getString("state"),
                        rs.getTimestamp("enqueued_at").toInstant(),
                        rs.getLong("version"),
                        rs.getObject("move_id", UUID.class),
                        rs.getObject("move_operation_id", UUID.class)));

        String cancellationTable = relationshipColumn.equals("picking_id")
                ? "stock_picking_cancellation_operations"
                : "stock_operation_cancellations";
        String cancellationIdColumn =
                relationshipColumn.equals("picking_id") ? "operation_id" : "cancellation_operation_id";
        List<CancellationUpgradeRow> cancellations = staged.query(
                "SELECT " + relationshipColumn + ", " + cancellationIdColumn
                        + ", state, started_at, updated_at, version FROM "
                        + cancellationTable
                        + " ORDER BY "
                        + relationshipColumn
                        + ", "
                        + cancellationIdColumn,
                (rs, rowNum) -> new CancellationUpgradeRow(
                        rs.getObject(relationshipColumn, UUID.class),
                        rs.getObject(cancellationIdColumn, UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getLong("version")));

        List<ShipmentUpgradeRow> shipments = staged.query(
                "SELECT id, " + relationshipColumn
                        + ", order_id, status, created_at, version FROM wms_shipments ORDER BY id",
                (rs, rowNum) -> new ShipmentUpgradeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject(relationshipColumn, UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("version")));
        return new StockOperationUpgradeSnapshot(operations, cancellations, shipments);
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
        UUID doneOrder = uuid("9104");
        UUID cancelledOrder = uuid("9105");
        seedOrder(staged, noMoveOrder, uuid("9201"), "NO-MOVE", "PENDING", Instant.parse("2026-08-18T01:00:00Z"));
        seedOrder(staged, confirmedOrder, uuid("9202"), "CONFIRMED", "PENDING", Instant.parse("2026-08-18T01:05:00Z"));
        seedOrder(staged, assignedOrder, uuid("9203"), "ASSIGNED", "PENDING", Instant.parse("2026-08-18T01:15:00Z"));
        seedOrder(staged, doneOrder, uuid("9204"), "DONE", "FULFILLED", Instant.parse("2026-08-18T01:25:00Z"));
        seedOrder(
                staged, cancelledOrder, uuid("9205"), "CANCELLED", "CANCELLED", Instant.parse("2026-08-18T01:35:00Z"));

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
        seedExecution(
                staged,
                doneOrder,
                uuid("9204"),
                uuid("9304"),
                uuid("9404"),
                executionLocation,
                "DONE",
                Instant.parse("2026-08-18T01:30:00Z"));
        seedQuantAndMoveLine(staged, uuid("9503"), uuid("9603"), uuid("9403"), executionLocation, 1, 1);
        seedQuantAndMoveLine(staged, uuid("9504"), uuid("9604"), uuid("9404"), executionLocation, 0, 2);
    }

    private static void seedQuantAndMoveLine(
            JdbcTemplate staged,
            UUID stockQuantId,
            UUID moveLineId,
            UUID moveId,
            UUID executionLocation,
            int reservedQuantity,
            int batchDay) {
        staged.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity)
        VALUES (?, ?, ?, 'SKU-A', ?, ?, 10, ?)
        """,
                stockQuantId,
                OrderFixtures.OWNER_ID,
                executionLocation,
                LocalDate.of(2026, 8, batchDay),
                LocalDate.of(2027, 8, batchDay),
                reservedQuantity);
        staged.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        VALUES (?, ?, ?, 1)
        """, moveLineId, moveId, stockQuantId);
    }

    private static void seedOrder(
            JdbcTemplate staged, UUID orderId, UUID lineId, String externalNo, String status, Instant receivedAt) {
        staged.update(
                """
        INSERT INTO orders
            (id, owner_id, external_order_no, ship_to_zone, ship_to_address,
             promised_delivery_date, dispatch_by, release_priority, facility_id,
             status, received_at, cancelled_at)
        VALUES (?, ?, ?, '100', 'Backfill fixture', ?, ?, 50, ?, ?, ?, ?)
        """,
                orderId,
                OrderFixtures.OWNER_ID,
                externalNo,
                LocalDate.parse("2026-08-22"),
                Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")),
                OrderFixtures.FACILITY_ID,
                status,
                Timestamp.from(receivedAt),
                "CANCELLED".equals(status) ? Timestamp.from(receivedAt.plusSeconds(60)) : null);
        staged.update("""
        INSERT INTO order_lines (id, order_id, line_no, owner_id, sku_code, quantity)
        VALUES (?, ?, 1, ?, 'SKU-A', 1)
        """, lineId, orderId, OrderFixtures.OWNER_ID);
    }

    private static void seedExecution(
            JdbcTemplate staged,
            UUID orderId,
            UUID lineId,
            UUID stockOperationId,
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
                stockOperationId,
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
                stockOperationId,
                OrderFixtures.OWNER_ID,
                sourceLocation,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                lineId,
                state,
                Timestamp.from(createdAt),
                "CONFIRMED".equals(state) ? null : Timestamp.from(createdAt));
    }

    private static void assertRepresentativeBackfill(JdbcTemplate staged) {
        assertThat(tableExists(staged, "allocation_demands")).isFalse();
        assertThat(tableExists(staged, "allocation_demand_lines")).isFalse();
        assertThat(tableExists(staged, "allocations")).isFalse();
        assertThat(tableExists(staged, "allocation_slices")).isFalse();
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM stock_operations", Integer.class))
                .isEqualTo(5);
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM stock_moves", Integer.class))
                .isEqualTo(5);
        assertThat(staged.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = current_schema() AND table_name = ?",
                        String.class,
                        "stock_moves"))
                .contains("source_line_id", "line_sequence")
                .doesNotContain("allocation_demand_id", "allocation_demand_line_id", "allocation_id", "order_line_id");
        assertThat(staged.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = current_schema() AND table_name = ?",
                        String.class,
                        "stock_move_lines"))
                .containsExactlyInAnyOrder("id", "move_id", "stock_pool_id", "quantity")
                .doesNotContain("allocation_slice_id");
        assertThat(staged.queryForObject("""
        SELECT COUNT(*)
          FROM stock_operations operation
          JOIN stock_moves move ON move.stock_operation_id = operation.id
         WHERE operation.state <> move.state
            OR operation.source_type <> 'ORDER'
            OR operation.policy_code <> 'SHIP_COMPLETE'
            OR move.line_sequence <> 1
        """, Integer.class)).isZero();
        assertThat(staged.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class))
                .isZero();
        assertThat(staged.queryForList("""
        SELECT stock_operation.source_id,
               cancellation.cancellation_operation_id,
               cancellation.state
          FROM stock_operation_cancellations cancellation
          JOIN stock_operations stock_operation
            ON stock_operation.id = cancellation.stock_operation_id
         ORDER BY stock_operation.source_id
        """))
                .extracting(
                        row -> row.get("source_id"),
                        row -> row.get("cancellation_operation_id"),
                        row -> row.get("state"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(uuid("9101").toString(), uuid("9701"), "COMPLETED"),
                        org.assertj.core.groups.Tuple.tuple(
                                uuid("9103").toString(), uuid("9703"), "EXTERNAL_CONFIRMED"));
        assertThat(staged.queryForObject("""
        SELECT COUNT(*)
          FROM stock_pools pool
         WHERE pool.reserved_quantity <> COALESCE((
               SELECT SUM(move_line.quantity)
                 FROM stock_move_lines move_line
                 JOIN stock_moves move ON move.id = move_line.move_id
                WHERE move_line.stock_pool_id = pool.id
                  AND move.state = 'ASSIGNED'), 0)
        """, Integer.class)).isZero();

        assertStockOperationSnapshot(
                staged, uuid("9101"), OrderFixtures.LOCATION_ID, Instant.parse("2026-08-18T01:00:00Z"), "CONFIRMED");
        assertStockOperationSnapshot(
                staged, uuid("9102"), uuid("c9"), Instant.parse("2026-08-18T01:10:00Z"), "CONFIRMED");
        assertStockOperationSnapshot(
                staged, uuid("9103"), uuid("c9"), Instant.parse("2026-08-18T01:20:00Z"), "ASSIGNED");
        assertStockOperationSnapshot(staged, uuid("9104"), uuid("c9"), Instant.parse("2026-08-18T01:30:00Z"), "DONE");
        assertStockOperationSnapshot(
                staged, uuid("9105"), OrderFixtures.LOCATION_ID, Instant.parse("2026-08-18T01:35:00Z"), "CANCELLED");
    }

    private static void assertStockOperationSnapshot(
            JdbcTemplate staged, UUID orderId, UUID locationId, Instant enqueuedAt, String state) {
        var snapshot = staged.queryForMap("""
        SELECT operation.from_location_id, operation.enqueued_at, operation.state, move.state AS move_state
          FROM stock_operations operation
          JOIN stock_moves move ON move.stock_operation_id = operation.id
         WHERE source_type = 'ORDER'
           AND source_id = ?
           AND allocation_unit_key = 'PRIMARY'
        """, orderId.toString());
        assertThat(snapshot.get("from_location_id")).isEqualTo(locationId);
        assertThat(((Timestamp) snapshot.get("enqueued_at")).toInstant()).isEqualTo(enqueuedAt);
        assertThat(snapshot.get("state")).isEqualTo(state);
        assertThat(snapshot.get("move_state")).isEqualTo(state);
    }

    private static boolean tableExists(JdbcTemplate staged, String tableName) {
        return Boolean.TRUE.equals(
                staged.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, tableName));
    }

    private static boolean columnExists(JdbcTemplate staged, String tableName, String columnName) {
        return Boolean.TRUE.equals(staged.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name = ?)
                """, Boolean.class, tableName, columnName));
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + "0".repeat(12 - suffix.length()) + suffix);
    }

    private record StockOperationUpgradeSnapshot(
            List<OperationUpgradeRow> operations,
            List<CancellationUpgradeRow> cancellations,
            List<ShipmentUpgradeRow> shipments) {}

    private record OperationUpgradeRow(
            UUID operationId,
            UUID operationTypeId,
            String sourceType,
            String sourceId,
            String allocationUnitKey,
            String state,
            Instant enqueuedAt,
            long version,
            UUID moveId,
            UUID moveOperationId) {}

    private record CancellationUpgradeRow(
            UUID operationId,
            UUID cancellationOperationId,
            String state,
            Instant startedAt,
            Instant updatedAt,
            long version) {}

    private record ShipmentUpgradeRow(
            UUID shipmentId, UUID operationId, UUID orderId, String state, Instant createdAt, long version) {}

    private enum CutoverMismatch {
        SOURCE_CONTENT("immutable source content drift") {
            @Override
            void inject(JdbcTemplate staged) {
                staged.update("UPDATE stock_moves SET line_sequence = 2 WHERE id = ?", uuid("9403"));
            }
        },
        PICKING_MOVE_STATE("picking and move states are not homogeneous") {
            @Override
            void inject(JdbcTemplate staged) {
                staged.update("UPDATE stock_pickings SET state = 'CANCELLED' WHERE id = ?", uuid("9303"));
            }
        },
        MOVEMENT_LINE_COVERAGE("movement-line coverage mismatch") {
            @Override
            void inject(JdbcTemplate staged) {
                staged.update("UPDATE stock_move_lines SET quantity = 2 WHERE id = ?", uuid("9603"));
            }
        },
        QUANT_COUNTER("stock reserved counter mismatch") {
            @Override
            void inject(JdbcTemplate staged) {
                staged.update("UPDATE stock_pools SET reserved_quantity = 0 WHERE id = ?", uuid("9503"));
            }
        },
        DOWNSTREAM_PICKING("downstream picking reference missing") {
            @Override
            void inject(JdbcTemplate staged) {
                staged.update(
                        """
                        INSERT INTO wms_shipments
                            (id, allocation_id, order_id, owner_id, facility_id,
                             created_at, dispatch_by, release_priority, status, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 50, 'CREATED', 0)
                        """,
                        uuid("9802"),
                        uuid("9993"),
                        uuid("9995"),
                        OrderFixtures.OWNER_ID,
                        OrderFixtures.FACILITY_ID,
                        Timestamp.from(Instant.parse("2026-08-18T02:00:00Z")),
                        Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")));
            }
        };

        private final String failureMessage;

        CutoverMismatch(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        abstract void inject(JdbcTemplate staged);
    }
}
