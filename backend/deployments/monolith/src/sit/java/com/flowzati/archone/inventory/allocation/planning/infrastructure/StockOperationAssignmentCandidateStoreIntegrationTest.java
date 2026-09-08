package com.flowzati.archone.inventory.allocation.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.store.OwnerAllocationPolicyStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcOwnerAllocationPolicyStoreAdapter;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockOperationAssignmentBacklogStoreAdapter;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store.JdbcStockOperationAssignmentCandidateStoreAdapter;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.repository.JpaStockMoveLineRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockMoveRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.repository.JpaStockOperationRepository;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockMoveStoreAdapter;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.store.StockOperationStoreAdapter;
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
    StockOperationStoreAdapter.class,
    StockMoveStoreAdapter.class,
    JdbcStockOperationAssignmentCandidateStoreAdapter.class,
    JdbcOwnerAllocationPolicyStoreAdapter.class,
    JdbcStockOperationAssignmentBacklogStoreAdapter.class,
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
    private JdbcStockOperationAssignmentCandidateStoreAdapter jdbcStockOperationAssignmentCandidateStoreAdapter;

    @Autowired
    private JdbcStockOperationAssignmentBacklogStoreAdapter jdbcStockOperationAssignmentBacklogStoreAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OwnerAllocationPolicyStore ownerAllocationPolicyStore;

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
        var queueHead = jdbcStockOperationAssignmentCandidateStoreAdapter
                .findNext(
                        new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-A"),
                        ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                .orElseThrow();

        assertThat(queueHead.stockOperationId()).isEqualTo(CANDIDATE_ID);
        assertThat(queueHead.moves())
                .extracting(com.flowzati.archone.inventory.allocation.domain.valueobject.StockMoveDemand::skuCode)
                .containsExactly("SKU-A", "SKU-B");
        assertThat(queueHead)
                .isEqualTo(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findDemand(CANDIDATE_ID)
                        .orElseThrow());
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter.findPredecessor(
                        queueHead, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID)))
                .get()
                .satisfies(predecessor -> {
                    assertThat(predecessor.stockOperationId()).isEqualTo(PREDECESSOR_ID);
                    assertThat(predecessor.sharedSkuCodes()).containsExactly("SKU-B");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
                .isZero();
    }

    @Test
    void emptyQueueHasNoCandidate() {
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter.findNext(
                        new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "MISSING"),
                        AllocationSequencePolicy.DISPATCH_DATE_FIRST))
                .isEmpty();
    }

    @Test
    @DisplayName("wake discovery is bounded, stock-aware and ordered by stable queue identity")
    void discoversBoundedPickingQueueKeysAndBacklogAge() {
        insertQuant(100, "SKU-A");
        insertQuant(101, "SKU-B");
        insertQuant(102, "SKU-C");

        assertThat(jdbcStockOperationAssignmentBacklogStoreAdapter.findQueueKeysWithAvailableStock(
                        LocalDate.parse("2026-08-27"), 2, null))
                .extracting(AssignmentQueueKey::skuCode)
                .containsExactly("SKU-A", "SKU-B");
    }

    @Test
    void scansPastExistingShortageQueuesWithAnExclusiveCursor() {
        insertQuant(100, "SKU-A");
        insertQuant(101, "SKU-B");
        insertQuant(102, "SKU-C");
        var date = LocalDate.parse("2026-08-27");
        var first = jdbcStockOperationAssignmentBacklogStoreAdapter.findQueueKeysWithAvailableStock(date, 2, null);
        var next = jdbcStockOperationAssignmentBacklogStoreAdapter.findQueueKeysWithAvailableStock(
                date, 2, first.getLast());
        assertThat(next).extracting(AssignmentQueueKey::skuCode).containsExactly("SKU-C");
        assertThat(jdbcStockOperationAssignmentBacklogStoreAdapter.findQueueKeysWithAvailableStock(
                        date, 2, next.getLast()))
                .isEmpty();
    }

    @Test
    void cancelledCandidateIsAbsentRatherThanExceptional() {
        var key = new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-A");
        var id = jdbcStockOperationAssignmentCandidateStoreAdapter
                .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                .orElseThrow()
                .stockOperationId();
        jdbcTemplate.update("UPDATE stock_operations SET state = 'CANCELLED' WHERE id = ?", id);
        jdbcTemplate.update("UPDATE stock_moves SET state = 'CANCELLED' WHERE stock_operation_id = ?", id);
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter.findDemand(id))
                .isEmpty();
    }

    @Test
    void ownerPolicyChangesBothQueueHeadAndSharedSkuPredecessor() {
        ownerAllocationPolicyStore.save(OrderFixtures.OWNER_ID, AllocationSequencePolicy.FIFO);
        var key = new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-B");
        jdbcTemplate.update(
                "UPDATE stock_operations SET dispatch_by = ? WHERE id = ?",
                Timestamp.from(DISJOINT_TIME),
                CANDIDATE_ID);
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                        .orElseThrow()
                        .stockOperationId())
                .isEqualTo(PREDECESSOR_ID);
        ownerAllocationPolicyStore.save(OrderFixtures.OWNER_ID, AllocationSequencePolicy.DISPATCH_DATE_FIRST);
        var urgent = jdbcStockOperationAssignmentCandidateStoreAdapter
                .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                .orElseThrow();
        assertThat(urgent.stockOperationId()).isEqualTo(CANDIDATE_ID);
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter.findPredecessor(
                        urgent, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID)))
                .isEmpty();
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findPredecessor(
                                jdbcStockOperationAssignmentCandidateStoreAdapter
                                        .findDemand(PREDECESSOR_ID)
                                        .orElseThrow(),
                                ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                        .orElseThrow()
                        .stockOperationId())
                .isEqualTo(CANDIDATE_ID);
        // No stock exists: shortage must not allow the lower-ranked operation to bypass this predecessor.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_pools", Integer.class))
                .isZero();
        ownerAllocationPolicyStore.save(OrderFixtures.OWNER_ID, AllocationSequencePolicy.FIFO);
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                        .orElseThrow()
                        .stockOperationId())
                .isEqualTo(PREDECESSOR_ID);
    }

    @Test
    void policyIsOwnerScopedAndEqualDispatchDatesUseEnqueueThenIdentity() {
        var other = uuid(999);
        jdbcTemplate.update("INSERT INTO owners(id, code, name) VALUES (?, 'POLICY-OTHER', 'Other owner')", other);
        ownerAllocationPolicyStore.save(OrderFixtures.OWNER_ID, AllocationSequencePolicy.DISPATCH_DATE_FIRST);
        assertThat(ownerAllocationPolicyStore.find(other)).isEqualTo(AllocationSequencePolicy.DISPATCH_DATE_FIRST);
        ownerAllocationPolicyStore.save(other, AllocationSequencePolicy.FIFO);
        assertThat(ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                .isEqualTo(AllocationSequencePolicy.DISPATCH_DATE_FIRST);
        jdbcTemplate.update(
                "UPDATE stock_operations SET dispatch_by = ?", Timestamp.from(CANDIDATE_TIME.plusSeconds(7200)));
        var key = new AssignmentQueueKey(OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-B");
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                        .orElseThrow()
                        .stockOperationId())
                .isEqualTo(PREDECESSOR_ID);
        jdbcTemplate.update(
                "UPDATE stock_operations SET enqueued_at = ? WHERE id = ?",
                Timestamp.from(PREDECESSOR_TIME),
                CANDIDATE_ID);
        assertThat(jdbcStockOperationAssignmentCandidateStoreAdapter
                        .findNext(key, ownerAllocationPolicyStore.find(OrderFixtures.OWNER_ID))
                        .orElseThrow()
                        .stockOperationId())
                .isEqualTo(PREDECESSOR_ID);
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
