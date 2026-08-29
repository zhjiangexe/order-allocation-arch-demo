package com.flowzati.archone.inventory.movement.visibility.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.repo.JdbcStockOperationViewStore;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, JdbcStockOperationViewStore.class})
@DisplayName("Stock operation bounded JDBC projection")
class StockOperationViewStoreIntegrationTest {

    private static final UUID CONFIRMED_A = uuid(1);
    private static final UUID CONFIRMED_B = uuid(2);
    private static final UUID ASSIGNED = uuid(3);
    private static final UUID MOVE_A = uuid(11);
    private static final UUID MOVE_B = uuid(12);
    private static final UUID QUANT_A = uuid(21);
    private static final UUID QUANT_B = uuid(22);
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00Z");

    @Autowired
    private JdbcStockOperationViewStore jdbcStockOperationViewStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCatalog() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A", "SKU-B");
    }

    @Test
    void oneStatementProjectionFoldsManyOperationsWithoutFollowUpReads() {
        insertOperation(CONFIRMED_B, "confirmed-b", "CONFIRMED", ENQUEUED_AT.plusSeconds(1));
        insertMove(uuid(32), CONFIRMED_B, "SKU-B", 1, 2, "CONFIRMED");
        insertOperation(CONFIRMED_A, "confirmed-a", "CONFIRMED", ENQUEUED_AT);
        insertMove(uuid(31), CONFIRMED_A, "SKU-A", 1, 1, "CONFIRMED");

        assertThat(jdbcStockOperationViewStore.findConfirmedOutbound(20))
                .extracting(view -> view.operation().stockOperationId())
                .containsExactly(CONFIRMED_A, CONFIRMED_B);
    }

    @Test
    void sourceProjectionPreservesMovesAndExactBatchReservationEvidence() {
        insertOperation(ASSIGNED, "assigned", "ASSIGNED", ENQUEUED_AT);
        insertQuant(QUANT_A, "SKU-A", "2026-09-30");
        insertQuant(QUANT_B, "SKU-B", "2026-10-31");
        insertMove(MOVE_B, ASSIGNED, "SKU-B", 3, 2, "ASSIGNED");
        insertMove(MOVE_A, ASSIGNED, "SKU-A", 2, 1, "ASSIGNED");
        insertLine(uuid(41), MOVE_A, QUANT_A, 2);
        insertLine(uuid(42), MOVE_B, QUANT_B, 3);

        var view = jdbcStockOperationViewStore
                .findBySource(new StockOperationSource(MovementSourceType.ORDER, "assigned", "PRIMARY"))
                .orElseThrow();

        assertThat(view.operation().stockOperationId()).isEqualTo(ASSIGNED);
        assertThat(view.moves()).extracting(move -> move.skuCode()).containsExactly("SKU-A", "SKU-B");
        assertThat(view.moves().getFirst().moveLines().getFirst().stockQuantId())
                .isEqualTo(QUANT_A);
        assertThat(view.moves().getFirst().moveLines().getFirst().quantity()).isEqualTo(2);
    }

    private void insertOperation(UUID id, String sourceId, String state, Instant enqueuedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO stock_operations
                    (id, stock_operation_type_id, direction, owner_id,
                     source_type, source_id, allocation_unit_key, policy_code, enqueued_at,
                     from_location_id, to_location_id, dispatch_by, release_priority, state, version)
                VALUES (?, ?, 'OUTBOUND', ?, 'ORDER', ?, 'PRIMARY', 'SHIP_COMPLETE', ?, ?, ?, ?, 50, ?, 0)
                """,
                id,
                MovementFixtures.OUTBOUND_TYPE_ID,
                OrderFixtures.OWNER_ID,
                sourceId,
                Timestamp.from(enqueuedAt),
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(enqueuedAt.plusSeconds(3600)),
                state);
    }

    private void insertMove(UUID id, UUID operationId, String skuCode, int quantity, int sequence, String state) {
        jdbcTemplate.update(
                """
                INSERT INTO stock_moves
                    (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
                     source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                id,
                operationId,
                OrderFixtures.OWNER_ID,
                skuCode,
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                "line-" + sequence,
                sequence,
                quantity,
                state,
                Timestamp.from(ENQUEUED_AT),
                "ASSIGNED".equals(state) ? Timestamp.from(ENQUEUED_AT.plusSeconds(60)) : null);
    }

    private void insertQuant(UUID id, String skuCode, String expiryDate) {
        jdbcTemplate.update(
                """
                INSERT INTO stock_pools
                    (id, owner_id, location_id, sku_code, in_date, expiry_date,
                     on_hand_quantity, reserved_quantity, version)
                VALUES (?, ?, ?, ?, ?, ?, 10, 0, 0)
                """,
                id,
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                skuCode,
                Date.valueOf("2026-08-01"),
                Date.valueOf(expiryDate));
    }

    private void insertLine(UUID id, UUID moveId, UUID quantId, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity) VALUES (?, ?, ?, ?)",
                id,
                moveId,
                quantId,
                quantity);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
