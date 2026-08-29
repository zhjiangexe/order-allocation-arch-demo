package com.flowzati.archone.inventory.movement.operation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.movement.application.repo.StockOperationReconciliationStore;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JdbcStockOperationReconciliationStore;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Direct database tests for the canonical source -> operation -> move -> move-line/quant model. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, JdbcStockOperationReconciliationStore.class})
@DisplayName("Stock movement canonical schema")
class StockMovementSchemaIntegrationTest {

    private static final UUID OWNER_ID = uuid(1);
    private static final UUID WAREHOUSE_ID = uuid(2);
    private static final UUID INTERNAL_LOCATION_ID = uuid(3);
    private static final UUID CUSTOMER_LOCATION_ID = uuid(4);
    private static final UUID SUPPLIER_LOCATION_ID = uuid(5);
    private static final UUID OUTBOUND_TYPE_ID = uuid(6);
    private static final UUID INBOUND_TYPE_ID = uuid(7);
    private static final UUID STOCK_QUANT_ID = uuid(8);
    private static final String SKU = "SKU-A";
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StockOperationReconciliationStore stockOperationReconciliationStore;

    @BeforeEach
    void seedCatalog() {
        jdbcTemplate.update("INSERT INTO owners (id, code, name) VALUES (?, 'OWNER-A', 'A')", OWNER_ID);
        jdbcTemplate.update(
                "INSERT INTO products (id, owner_id, product_code, name, temperature_zone) "
                        + "VALUES (?, ?, 'P-A', 'P', 'AMBIENT')",
                uuid(9),
                OWNER_ID);
        jdbcTemplate.update(
                "INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram) "
                        + "VALUES (?, ?, ?, 'P-A', 'spec', 100)",
                uuid(10),
                OWNER_ID,
                SKU);
        jdbcTemplate.update("INSERT INTO facilities (id, code, name) VALUES (?, 'WH-A', 'A')", WAREHOUSE_ID);
        jdbcTemplate.update(
                "INSERT INTO owner_facilities (owner_id, facility_id) VALUES (?, ?)", OWNER_ID, WAREHOUSE_ID);
        insertLocation(INTERNAL_LOCATION_ID, WAREHOUSE_ID, "WH-A/Stock", "INTERNAL");
        insertLocation(CUSTOMER_LOCATION_ID, null, "Customers", "CUSTOMER");
        insertLocation(SUPPLIER_LOCATION_ID, null, "Vendors", "SUPPLIER");
        insertPickingType(OUTBOUND_TYPE_ID, "OUTBOUND", INTERNAL_LOCATION_ID, CUSTOMER_LOCATION_ID);
        insertPickingType(INBOUND_TYPE_ID, "INBOUND", SUPPLIER_LOCATION_ID, INTERNAL_LOCATION_ID);
        insertQuant(0);
    }

    @Nested
    @DisplayName("Canonical schema shape")
    class CanonicalSchemaShape {

        @Test
        @DisplayName("parallel demand and reservation ledgers are absent")
        void hasNoParallelDemandOrReservationLedger() {
            assertThat(tableNames())
                    .contains("stock_operations", "stock_moves", "stock_move_lines", "stock_pools")
                    .doesNotContain(
                            "stock_reservations",
                            "allocation_demands",
                            "allocation_demand_lines",
                            "allocations",
                            "allocation_slices");
        }

        @Test
        @DisplayName("operation carries source and policy but no SKU or quantity")
        void pickingSeparatesSourceIdentityFromMovementQuantity() {
            assertThat(columnNames("stock_operations"))
                    .contains(
                            "source_type",
                            "source_id",
                            "allocation_unit_key",
                            "policy_code",
                            "enqueued_at",
                            "from_location_id",
                            "to_location_id",
                            "state",
                            "version")
                    .doesNotContain("order_id", "sku_code", "quantity", "demand_quantity");
        }

        @Test
        @DisplayName("move carries the source line and route without source-specific foreign keys")
        void moveCarriesCanonicalLineIdentityAndRoute() {
            assertThat(columnNames("stock_moves"))
                    .contains(
                            "stock_operation_id",
                            "source_line_id",
                            "line_sequence",
                            "sku_code",
                            "demand_quantity",
                            "from_location_id",
                            "to_location_id")
                    .doesNotContain(
                            "allocation_demand_id", "allocation_demand_line_id", "allocation_id", "order_line_id");
        }

        @Test
        @DisplayName("move line is only current batch detail and owns no lifecycle")
        void moveLineCarriesOnlyBatchAndQuantity() {
            assertThat(columnNames("stock_move_lines"))
                    .containsExactlyInAnyOrder("id", "move_id", "stock_pool_id", "quantity")
                    .doesNotContain("state", "status", "allocation_slice_id", "assigned_at", "created_at");
        }
    }

    @Nested
    @DisplayName("Source and row-local constraints")
    class SourceAndRowLocalConstraints {

        @Test
        @DisplayName("one source allocation unit creates at most one operation")
        void rejectsDuplicateSourceUnit() {
            insertConfirmedGroup(uuid(20), uuid(21), "ORDER-1", "LINE-1", 1);

            assertThatThrownBy(() -> insertOutboundPicking(uuid(22), "ORDER-1", "PRIMARY", "CONFIRMED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("one source line creates at most one move inside a operation")
        void rejectsDuplicateSourceLine() {
            UUID stockOperationId = uuid(23);
            insertConfirmedGroup(stockOperationId, uuid(24), "ORDER-2", "LINE-1", 1);

            assertThatThrownBy(() -> insertMove(
                            uuid(25),
                            stockOperationId,
                            "LINE-1",
                            2,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID,
                            2,
                            "CONFIRMED",
                            null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("source-agnostic inbound records need no source document identity")
        void acceptsSourceAgnosticInboundGroup() {
            UUID stockOperationId = uuid(26);
            insertInboundPicking(stockOperationId, "CONFIRMED");
            insertMove(
                    uuid(27),
                    stockOperationId,
                    null,
                    null,
                    SUPPLIER_LOCATION_ID,
                    INTERNAL_LOCATION_ID,
                    3,
                    "CONFIRMED",
                    null);

            forceDeferredConstraints();

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT source_type FROM stock_operations WHERE id = ?", String.class, stockOperationId))
                    .isNull();
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT source_line_id FROM stock_moves WHERE stock_operation_id = ?",
                            String.class,
                            stockOperationId))
                    .isNull();
        }

        @Test
        @DisplayName("move requires both route endpoints")
        void rejectsMissingRouteEndpoint() {
            UUID stockOperationId = uuid(28);
            insertOutboundPicking(stockOperationId, "ORDER-3", "PRIMARY", "CONFIRMED");

            assertThatThrownBy(() -> insertMove(
                            uuid(29), stockOperationId, "LINE-1", 1, INTERNAL_LOCATION_ID, null, 1, "CONFIRMED", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("state is limited to the lifecycle implemented by Inventory")
        void rejectsUnknownMoveState() {
            UUID stockOperationId = uuid(30);
            insertOutboundPicking(stockOperationId, "ORDER-4", "PRIMARY", "CONFIRMED");

            assertThatThrownBy(() -> insertMove(
                            uuid(31),
                            stockOperationId,
                            "LINE-1",
                            1,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID,
                            1,
                            "WAITING",
                            null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("confirmed move cannot carry assignedAt")
        void rejectsAssignedTimeOnConfirmedMove() {
            UUID stockOperationId = uuid(32);
            insertOutboundPicking(stockOperationId, "ORDER-5", "PRIMARY", "CONFIRMED");

            assertThatThrownBy(() -> insertMove(
                            uuid(33),
                            stockOperationId,
                            "LINE-1",
                            1,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID,
                            1,
                            "CONFIRMED",
                            ENQUEUED_AT.plusSeconds(10)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("assigned move requires assignedAt")
        void rejectsAssignedMoveWithoutAssignedTime() {
            UUID stockOperationId = uuid(34);
            insertOutboundPicking(stockOperationId, "ORDER-6", "PRIMARY", "ASSIGNED");

            assertThatThrownBy(() -> insertMove(
                            uuid(35),
                            stockOperationId,
                            "LINE-1",
                            1,
                            INTERNAL_LOCATION_ID,
                            CUSTOMER_LOCATION_ID,
                            1,
                            "ASSIGNED",
                            null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Cross-row operation invariants")
    class CrossRowOperationInvariants {

        @Test
        @DisplayName("operation and every move must have one homogeneous state")
        void rejectsMixedPickingAndMoveStates() {
            UUID stockOperationId = uuid(40);
            insertOutboundPicking(stockOperationId, "ORDER-7", "PRIMARY", "ASSIGNED");
            insertMove(
                    uuid(41),
                    stockOperationId,
                    "LINE-1",
                    1,
                    INTERNAL_LOCATION_ID,
                    CUSTOMER_LOCATION_ID,
                    3,
                    "CONFIRMED",
                    null);

            assertThatThrownBy(StockMovementSchemaIntegrationTest.this::forceDeferredConstraints)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("states must be homogeneous");
        }

        @Test
        @DisplayName("assigned move lines must exactly cover demand quantity")
        void rejectsIncompleteAssignedCoverage() {
            UUID stockOperationId = uuid(42);
            UUID moveId = uuid(43);
            insertOutboundPicking(stockOperationId, "ORDER-8", "PRIMARY", "ASSIGNED");
            insertMove(
                    moveId,
                    stockOperationId,
                    "LINE-1",
                    1,
                    INTERNAL_LOCATION_ID,
                    CUSTOMER_LOCATION_ID,
                    3,
                    "ASSIGNED",
                    ENQUEUED_AT.plusSeconds(10));
            insertMoveLine(uuid(44), moveId, 2);
            jdbcTemplate.update("UPDATE stock_pools SET reserved_quantity = 2 WHERE id = ?", STOCK_QUANT_ID);

            assertThatThrownBy(StockMovementSchemaIntegrationTest.this::forceDeferredConstraints)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("exactly match");
        }

        @Test
        @DisplayName("confirmed and cancelled moves cannot retain move lines")
        void rejectsMoveLinesOutsideAssignedOrDoneLifecycle() {
            UUID stockOperationId = uuid(45);
            UUID moveId = uuid(46);
            insertConfirmedGroup(stockOperationId, moveId, "ORDER-9", "LINE-1", 3);
            insertMoveLine(uuid(47), moveId, 3);

            assertThatThrownBy(StockMovementSchemaIntegrationTest.this::forceDeferredConstraints)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("exactly match");
        }

        @Test
        @DisplayName("quant reserved counter must equal active assigned move-line quantities")
        void rejectsReservedCounterDrift() {
            UUID stockOperationId = uuid(48);
            UUID moveId = uuid(49);
            insertAssignedGroup(stockOperationId, moveId, "ORDER-10", 3);
            jdbcTemplate.update("UPDATE stock_pools SET reserved_quantity = 2 WHERE id = ?", STOCK_QUANT_ID);

            assertThatThrownBy(StockMovementSchemaIntegrationTest.this::forceDeferredConstraints)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("reserved quantity must equal");
        }

        @Test
        @DisplayName("one complete assigned group satisfies lifecycle, coverage and counter invariants")
        void acceptsExactAssignedOperationGroup() {
            UUID stockOperationId = uuid(50);
            insertAssignedGroup(stockOperationId, uuid(51), "ORDER-11", 3);

            forceDeferredConstraints();

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT state FROM stock_operations WHERE id = ?", String.class, stockOperationId))
                    .isEqualTo("ASSIGNED");
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT reserved_quantity FROM stock_pools WHERE id = ?", Integer.class, STOCK_QUANT_ID))
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Operational reconciliation")
    class OperationalReconciliation {

        @Test
        @DisplayName("reports a clean canonical database as healthy")
        void reportsHealthyState() {
            assertThat(stockOperationReconciliationStore.inspect(20).healthy()).isTrue();
        }

        @Test
        @DisplayName("finds operation and move state drift without a legacy demand join")
        void findsHeterogeneousPicking() {
            UUID stockOperationId = uuid(60);
            insertOutboundPicking(stockOperationId, "ORDER-12", "PRIMARY", "ASSIGNED");
            insertMove(
                    uuid(61),
                    stockOperationId,
                    "LINE-1",
                    1,
                    INTERNAL_LOCATION_ID,
                    CUSTOMER_LOCATION_ID,
                    3,
                    "CONFIRMED",
                    null);

            assertThat(stockOperationReconciliationStore.inspect(20).heterogeneousOperationIds())
                    .containsExactly(stockOperationId);
        }

        @Test
        @DisplayName("finds move-line coverage drift from movement facts")
        void findsMoveLineCoverageMismatch() {
            UUID stockOperationId = uuid(62);
            UUID moveId = uuid(63);
            insertOutboundPicking(stockOperationId, "ORDER-13", "PRIMARY", "ASSIGNED");
            insertMove(
                    moveId,
                    stockOperationId,
                    "LINE-1",
                    1,
                    INTERNAL_LOCATION_ID,
                    CUSTOMER_LOCATION_ID,
                    3,
                    "ASSIGNED",
                    ENQUEUED_AT.plusSeconds(10));

            assertThat(stockOperationReconciliationStore.inspect(20).moveLineCoverageMismatchMoveIds())
                    .containsExactly(moveId);
        }

        @Test
        @DisplayName("finds quant counter drift from active assigned move lines")
        void findsReservedCounterMismatch() {
            jdbcTemplate.update("UPDATE stock_pools SET reserved_quantity = 1 WHERE id = ?", STOCK_QUANT_ID);

            assertThat(stockOperationReconciliationStore.inspect(20).reservedCounterMismatchStockQuantIds())
                    .containsExactly(STOCK_QUANT_ID);
        }
    }

    private void insertConfirmedGroup(
            UUID stockOperationId, UUID moveId, String sourceId, String sourceLineId, int quantity) {
        insertOutboundPicking(stockOperationId, sourceId, "PRIMARY", "CONFIRMED");
        insertMove(
                moveId,
                stockOperationId,
                sourceLineId,
                1,
                INTERNAL_LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                quantity,
                "CONFIRMED",
                null);
    }

    private void insertAssignedGroup(UUID stockOperationId, UUID moveId, String sourceId, int quantity) {
        insertOutboundPicking(stockOperationId, sourceId, "PRIMARY", "ASSIGNED");
        insertMove(
                moveId,
                stockOperationId,
                "LINE-1",
                1,
                INTERNAL_LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                quantity,
                "ASSIGNED",
                ENQUEUED_AT.plusSeconds(10));
        insertMoveLine(uuid(90), moveId, quantity);
        jdbcTemplate.update("UPDATE stock_pools SET reserved_quantity = ? WHERE id = ?", quantity, STOCK_QUANT_ID);
    }

    private void insertOutboundPicking(UUID stockOperationId, String sourceId, String allocationUnitKey, String state) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_operations
            (id, stock_operation_type_id, direction, owner_id,
             source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
             from_location_id, to_location_id, dispatch_by, release_priority, state, version)
        VALUES (?, ?, 'OUTBOUND', ?, 'ORDER', ?, ?, 'SHIP_COMPLETE', ?, ?, ?, ?, 50, ?, 0)
        """,
                stockOperationId,
                OUTBOUND_TYPE_ID,
                OWNER_ID,
                sourceId,
                allocationUnitKey,
                Timestamp.from(ENQUEUED_AT),
                INTERNAL_LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                Timestamp.from(ENQUEUED_AT.plusSeconds(3600)),
                state);
    }

    private void insertInboundPicking(UUID stockOperationId, String state) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_operations
            (id, stock_operation_type_id, direction, owner_id,
             source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
             from_location_id, to_location_id, dispatch_by, release_priority, state, version)
        VALUES (?, ?, 'INBOUND', ?, NULL, NULL, NULL, NULL, NULL, ?, ?, NULL, NULL, ?, 0)
        """, stockOperationId, INBOUND_TYPE_ID, OWNER_ID, SUPPLIER_LOCATION_ID, INTERNAL_LOCATION_ID, state);
    }

    private void insertMove(
            UUID moveId,
            UUID stockOperationId,
            String sourceLineId,
            Integer lineSequence,
            UUID fromLocationId,
            UUID toLocationId,
            int quantity,
            String state,
            Instant assignedAt) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
                moveId,
                stockOperationId,
                OWNER_ID,
                SKU,
                fromLocationId,
                toLocationId,
                sourceLineId,
                lineSequence,
                quantity,
                state,
                Timestamp.from(ENQUEUED_AT),
                assignedAt == null ? null : Timestamp.from(assignedAt));
    }

    private void insertMoveLine(UUID moveLineId, UUID moveId, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity) VALUES (?, ?, ?, ?)",
                moveLineId,
                moveId,
                STOCK_QUANT_ID,
                quantity);
    }

    private void insertQuant(int reservedQuantity) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, ?, ?, ?, 10, ?, 0)
        """,
                STOCK_QUANT_ID,
                OWNER_ID,
                INTERNAL_LOCATION_ID,
                SKU,
                Date.valueOf(LocalDate.of(2026, 8, 1)),
                Date.valueOf(LocalDate.of(2027, 8, 1)),
                reservedQuantity);
    }

    private void insertLocation(UUID id, UUID facilityId, String code, String usage) {
        jdbcTemplate.update(
                "INSERT INTO stock_locations (id, facility_id, code, name, usage) VALUES (?, ?, ?, ?, ?)",
                id,
                facilityId,
                code,
                code,
                usage);
    }

    private void insertPickingType(UUID id, String code, UUID fromLocationId, UUID toLocationId) {
        jdbcTemplate.update("""
        INSERT INTO stock_operation_types
            (id, facility_id, code, name, default_from_location_id, default_to_location_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """, id, WAREHOUSE_ID, code, code, fromLocationId, toLocationId);
    }

    private void forceDeferredConstraints() {
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    private List<String> tableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }

    private List<String> columnNames(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class,
                table);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
