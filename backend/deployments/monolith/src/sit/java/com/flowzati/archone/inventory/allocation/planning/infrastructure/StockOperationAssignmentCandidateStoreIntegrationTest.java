package com.flowzati.archone.inventory.allocation.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.application.valueobject.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockMoveRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockMoveStoreImpl;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockOperationStoreImpl;
import com.flowzati.archone.inventory.reservation.infrastructure.persistence.jpa.repository.JpaStockMoveLineRepository;
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

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    StockOperationStoreImpl.class,
    StockMoveStoreImpl.class,
    JdbcStockOperationAssignmentCandidateStore.class,
    JdbcStockOperationAssignmentBacklogStore.class,
    StockOperationAssignmentCandidateStoreIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("Pending operation PostgreSQL selection")
class StockOperationAssignmentCandidateStoreIntegrationTest {

    private static final Instant DISJOINT_TIME = Instant.parse("2026-08-27T00:00:00Z");
    private static final Instant PREDECESSOR_TIME = Instant.parse("2026-08-27T00:01:00Z");
    private static final Instant CANDIDATE_TIME = Instant.parse("2026-08-27T00:02:00Z");
    private static final UUID DISJOINT_ID = uuid(10);
    private static final UUID PREDECESSOR_ID = uuid(20);
    private static final UUID CANDIDATE_ID = uuid(30);

    @Autowired
    private JdbcStockOperationAssignmentCandidateStore jdbcStockOperationAssignmentCandidateStore;

    @Autowired
    private JdbcStockOperationAssignmentBacklogStore jdbcStockOperationAssignmentBacklogStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedMovementGroups() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A", "SKU-B", "SKU-C");
        insertPicking(DISJOINT_ID, "disjoint", DISJOINT_TIME);
        insertMove(DISJOINT_ID, 11, 1, "SKU-C");
        insertPicking(PREDECESSOR_ID, "predecessor", PREDECESSOR_TIME);
        insertMove(PREDECESSOR_ID, 21, 1, "SKU-B");
        insertPicking(CANDIDATE_ID, "candidate", CANDIDATE_TIME);
        insertMove(CANDIDATE_ID, 31, 1, "SKU-A");
        insertMove(CANDIDATE_ID, 32, 2, "SKU-B");
        entityManager.clear();
    }

    @Test
    @DisplayName("earlier disjoint operation is independent while an unavailable shared-SKU operation still blocks")
    void appliesTheExactSharedSkuPredecessorRelation() {
        var queueHead = jdbcStockOperationAssignmentCandidateStore
                .findNext(new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-A"))
                .orElseThrow();

        assertThat(queueHead.demand().stockOperationId()).isEqualTo(CANDIDATE_ID);
        assertThat(queueHead.predecessor()).get().satisfies(predecessor -> {
            assertThat(predecessor.stockOperationId()).isEqualTo(PREDECESSOR_ID);
            assertThat(predecessor.sharedSkuCodes()).containsExactly("SKU-B");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("wake discovery is bounded, stock-aware and ordered by operation enqueue position")
    void discoversBoundedPickingQueueKeysAndBacklogAge() {
        insertQuant(100, "SKU-A");
        insertQuant(101, "SKU-B");
        insertQuant(102, "SKU-C");

        assertThat(jdbcStockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(
                        LocalDate.parse("2026-08-27"), 2))
                .extracting(AssignmentQueueKey::skuCode)
                .containsExactly("SKU-C", "SKU-B");
    }

    private void insertPicking(UUID stockOperationId, String sourceId, Instant enqueuedAt) {
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
                sourceId,
                Timestamp.from(enqueuedAt),
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                Timestamp.from(enqueuedAt.plusSeconds(3600)));
    }

    private void insertMove(UUID stockOperationId, int seed, int lineSequence, String skuCode) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_moves
            (id, stock_operation_id, owner_id, sku_code, from_location_id, to_location_id,
             source_line_id, line_sequence, demand_quantity, state, created_at, assigned_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'CONFIRMED', ?, NULL, 0)
        """,
                uuid(seed),
                stockOperationId,
                OrderFixtures.OWNER_ID,
                skuCode,
                OrderFixtures.LOCATION_ID,
                MovementFixtures.CUSTOMERS_LOCATION_ID,
                "line-" + seed,
                lineSequence,
                Timestamp.from(CANDIDATE_TIME));
    }

    private void insertQuant(int seed, String skuCode) {
        jdbcTemplate.update(
                """
        INSERT INTO stock_pools
            (id, owner_id, location_id, sku_code, in_date, expiry_date,
             on_hand_quantity, reserved_quantity, version)
        VALUES (?, ?, ?, ?, ?, ?, 10, 0, 0)
        """,
                uuid(seed),
                OrderFixtures.OWNER_ID,
                OrderFixtures.LOCATION_ID,
                skuCode,
                Date.valueOf("2026-08-01"),
                Date.valueOf("2026-12-31"));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(
            basePackageClasses = {
                JpaStockOperationRepository.class,
                JpaStockMoveRepository.class,
                JpaStockMoveLineRepository.class
            })
    static class RepositoryConfiguration {}
}
