package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    AllocationDemandRepositoryImpl.class,
    PendingDemandSelectionImpl.class,
    AllocationCancellationOperationRepositoryImpl.class,
    AllocationDemandPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("AllocationDemand PostgreSQL persistence adapters")
class AllocationDemandPersistenceIntegrationTest {

    private static final Instant REQUIRED_BY = Instant.parse("2026-08-20T01:02:03Z");
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-18T01:02:03Z");

    @Autowired
    private AllocationDemandRepositoryImpl demandRepository;

    @Autowired
    private PendingDemandSelectionImpl pendingDemandSelection;

    @Autowired
    private JpaAllocationDemandRepository jpaDemandRepository;

    @Autowired
    private AllocationCancellationOperationRepositoryImpl cancellationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedCatalog() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-A", "SKU-B", "SKU-C");
    }

    @Test
    @DisplayName("持久化 source-agnostic demand、canonical lines、狀態與 optimistic version")
    void shouldRoundTripDemandByAllocationOwnedIdentity() {
        AllocationDemand accepted = demand(uuid(1), AllocationSourceType.TRANSFER, "transfer-42");

        AllocationDemand saved = demandRepository.save(accepted);
        entityManager.flush();
        entityManager.clear();

        AllocationDemand found = demandRepository
                .findBySource(new SourceAllocationUnit(AllocationSourceType.TRANSFER, "transfer-42", "LEG-A"))
                .orElseThrow();

        assertThat(saved.version()).isZero();
        assertThat(found.id()).isEqualTo(uuid(1));
        assertThat(found.source().sourceType()).isEqualTo(AllocationSourceType.TRANSFER);
        assertThat(found.ownerId()).isEqualTo(OrderFixtures.OWNER_ID);
        assertThat(found.facilityId()).isEqualTo(OrderFixtures.FACILITY_ID);
        assertThat(found.locationId()).isEqualTo(OrderFixtures.LOCATION_ID);
        assertThat(found.requiredBy()).isEqualTo(REQUIRED_BY);
        assertThat(found.enqueuedAt()).isEqualTo(ENQUEUED_AT);
        assertThat(found.status()).isEqualTo(AllocationDemandStatus.PENDING);
        assertThat(found.acceptedContentVersion()).isEqualTo(1);
        assertThat(found.lines())
                .extracting(line -> line.sourceLineId() + ":" + line.lineSequence())
                .containsExactly("line-a:1", "line-b:2");

        found.markAllocated();
        demandRepository.save(found);
        entityManager.flush();
        entityManager.clear();
        AllocationDemand allocated = demandRepository.findById(found.id()).orElseThrow();

        assertThat(allocated.status()).isEqualTo(AllocationDemandStatus.ALLOCATED);
        assertThat(allocated.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("source allocation-unit identity 在資料庫中不可重複")
    void shouldRejectDuplicateSourceAllocationUnit() {
        demandRepository.save(demand(uuid(10), AllocationSourceType.MANUAL, "manual-7"));
        entityManager.flush();

        AllocationDemand duplicate = demand(uuid(20), AllocationSourceType.MANUAL, "manual-7");
        assertThatThrownBy(() -> {
                    demandRepository.save(duplicate);
                    entityManager.flush();
                })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("external-confirmed cancellation checkpoint 可跨 transaction 恢復")
    void shouldPersistCancellationCheckpoint() {
        AllocationDemand accepted =
                demandRepository.save(demand(uuid(30), AllocationSourceType.REPLENISHMENT, "replenishment-9"));
        UUID operationId = uuid(31);
        Instant startedAt = Instant.parse("2026-08-18T03:00:00Z");
        AllocationCancellationOperation operation =
                AllocationCancellationOperation.start(accepted.id(), operationId, startedAt);

        operation.confirmExternally(startedAt.plusSeconds(1));
        cancellationRepository.save(operation);
        entityManager.flush();
        entityManager.clear();

        AllocationCancellationOperation resumed =
                cancellationRepository.find(accepted.id(), operationId).orElseThrow();
        assertThat(resumed.state()).isEqualTo(AllocationCancellationState.EXTERNAL_CONFIRMED);

        resumed.completeLocally(startedAt.plusSeconds(2));
        cancellationRepository.save(resumed);
        entityManager.flush();
        entityManager.clear();
        AllocationCancellationOperation completed =
                cancellationRepository.find(accepted.id(), operationId).orElseThrow();

        assertThat(completed.state()).isEqualTo(AllocationCancellationState.COMPLETED);
        assertThat(completed.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("candidate query 帶回每個 required SKU 的 earlier predecessor 且排除 disjoint queue")
    void shouldLoadCrossSkuFifoContextWithoutCouplingDisjointQueues() {
        AllocationDemand earlierB = queueDemand(
                40,
                "earlier-b",
                Instant.parse("2026-08-18T00:00:00Z"),
                List.of(new AllocationDemandLineRequest("b", "SKU-B", 1)));
        AllocationDemand candidateAb = queueDemand(
                50,
                "candidate-ab",
                Instant.parse("2026-08-18T00:01:00Z"),
                List.of(
                        new AllocationDemandLineRequest("a", "SKU-A", 1),
                        new AllocationDemandLineRequest("b", "SKU-B", 1)));
        AllocationDemand laterA = queueDemand(
                60,
                "later-a",
                Instant.parse("2026-08-18T00:02:00Z"),
                List.of(new AllocationDemandLineRequest("a", "SKU-A", 1)));
        AllocationDemand disjoint = queueDemand(
                70,
                "disjoint-c",
                Instant.parse("2026-08-17T23:59:00Z"),
                List.of(new AllocationDemandLineRequest("c", "SKU-C", 1)));
        List.of(earlierB, candidateAb, laterA, disjoint).forEach(demandRepository::save);
        entityManager.flush();
        List.of(earlierB, candidateAb, laterA, disjoint).forEach(this::seedConfirmedExecution);
        entityManager.flush();
        entityManager.clear();

        PendingDemandQueuePosition queueHeadPosition = pendingDemandSelection
                .findQueueHead(new AllocationDemandQueueKey(
                        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-A"))
                .orElseThrow();

        assertThat(queueHeadPosition.demand().id()).isEqualTo(candidateAb.id());
        assertThat(queueHeadPosition.requiredQueueHeads())
                .extracting(queueHead -> queueHead.skuCode(), queueHead -> queueHead.allocationDemandId())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("SKU-A", candidateAb.id()),
                        org.assertj.core.groups.Tuple.tuple("SKU-B", earlierB.id()));
        assertThat(queueHeadPosition.isHeadOfEveryRequiredQueue()).isFalse();

        PendingDemandQueuePosition exactPosition = pendingDemandSelection.positionOf(candidateAb);
        assertThat(exactPosition.requiredQueueHeads())
                .extracting(queueHead -> queueHead.skuCode(), queueHead -> queueHead.allocationDemandId())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("SKU-A", candidateAb.id()),
                        org.assertj.core.groups.Tuple.tuple("SKU-B", earlierB.id()));
        assertThat(exactPosition.isHeadOfEveryRequiredQueue()).isFalse();
    }

    @Test
    @DisplayName("缺少 execution reference 的 pending demand 會被 anomaly query 隔離而不進候選")
    void shouldIsolatePendingExecutionAnomaly() {
        AllocationDemand malformed = queueDemand(
                80,
                "malformed",
                Instant.parse("2026-08-18T00:00:00Z"),
                List.of(new AllocationDemandLineRequest("a", "SKU-A", 1)));
        demandRepository.save(malformed);
        entityManager.flush();
        entityManager.clear();

        assertThat(jpaDemandRepository.findPendingExecutionAnomalyIds(10)).containsExactly(malformed.id());
        assertThat(pendingDemandSelection.findQueueHead(new AllocationDemandQueueKey(
                        OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID, OrderFixtures.LOCATION_ID, "SKU-A")))
                .isEmpty();
    }

    @Test
    @DisplayName("pending scope 與 demand execution lookup 使用 cutover indexes")
    void shouldUseDemandFirstCutoverIndexes() {
        AllocationDemand queued = queueDemand(
                90,
                "query-plan",
                Instant.parse("2026-08-18T00:00:00Z"),
                List.of(new AllocationDemandLineRequest("a", "SKU-A", 1)));
        demandRepository.save(queued);
        entityManager.flush();
        seedConfirmedExecution(queued);

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String pendingPlan = String.join(
                "\n",
                jdbcTemplate.queryForList(
                        """
        EXPLAIN
        SELECT d.id
          FROM allocation_demands d
         WHERE d.status = 'PENDING'
           AND d.owner_id = ?
           AND d.facility_id = ?
           AND d.location_id = ?
         ORDER BY d.enqueued_at, d.id
         LIMIT 10
        """,
                        String.class,
                        OrderFixtures.OWNER_ID,
                        OrderFixtures.FACILITY_ID,
                        OrderFixtures.LOCATION_ID));
        String executionPlan = String.join("\n", jdbcTemplate.queryForList("""
        EXPLAIN
        SELECT move.id
          FROM stock_moves move
         WHERE move.allocation_demand_id = ?
        ORDER BY move.allocation_demand_line_id
        """, String.class, queued.id()));
        String skuQueuePlan = String.join("\n", jdbcTemplate.queryForList("""
        EXPLAIN
        SELECT line.allocation_demand_id
          FROM allocation_demand_lines line
         WHERE line.sku_code = ?
         ORDER BY line.allocation_demand_id
        """, String.class, "SKU-A"));
        String stockPlan = String.join(
                "\n",
                jdbcTemplate.queryForList(
                        """
        EXPLAIN
        SELECT pool.id
          FROM stock_pools pool
         WHERE pool.owner_id = ?
           AND pool.location_id = ?
           AND pool.sku_code = ?
           AND pool.expiry_date >= CURRENT_DATE
           AND pool.on_hand_quantity > pool.reserved_quantity
         ORDER BY pool.expiry_date, pool.in_date, pool.id
        """, String.class, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, "SKU-A"));

        assertThat(pendingPlan).contains("idx_allocation_demands_pending_scope");
        assertThat(executionPlan).contains("uq_stock_moves_allocation_demand_line");
        assertThat(skuQueuePlan).contains("idx_allocation_demand_lines_sku_queue");
        assertThat(stockPlan).contains("idx_stock_pools_fefo");
    }

    private void seedConfirmedExecution(AllocationDemand demand) {
        for (var line : demand.lines()) {
            jdbcTemplate.update(
                    """
          INSERT INTO stock_moves
              (id, picking_id, owner_id, sku_code, from_location_id, to_location_id,
               allocation_demand_id, allocation_demand_line_id, source_line_id,
               order_line_id, demand_quantity, state, created_at)
          VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'CONFIRMED', ?)
          """,
                    UUID.randomUUID(),
                    demand.ownerId(),
                    line.skuCode(),
                    demand.locationId(),
                    MovementFixtures.CUSTOMERS_LOCATION_ID,
                    demand.id(),
                    line.id(),
                    line.sourceLineId(),
                    line.quantity(),
                    java.sql.Timestamp.from(demand.enqueuedAt()));
        }
    }

    private static AllocationDemand demand(UUID demandId, AllocationSourceType sourceType, String sourceId) {
        AtomicInteger lineIds = new AtomicInteger(100);
        return AllocationDemand.accept(
                demandId,
                new SourceAllocationUnit(sourceType, sourceId, "LEG-A"),
                OrderFixtures.OWNER_ID,
                OrderFixtures.FACILITY_ID,
                OrderFixtures.LOCATION_ID,
                REQUIRED_BY,
                50,
                ENQUEUED_AT,
                List.of(
                        new AllocationDemandLineRequest("line-b", "SKU-B", 3),
                        new AllocationDemandLineRequest("line-a", "SKU-A", 2)),
                () -> uuid(lineIds.getAndIncrement()));
    }

    private static AllocationDemand queueDemand(
            int seed, String sourceId, Instant enqueuedAt, List<AllocationDemandLineRequest> lines) {
        AtomicInteger lineIds = new AtomicInteger(seed + 1);
        return AllocationDemand.accept(
                uuid(seed),
                new SourceAllocationUnit(AllocationSourceType.MANUAL, sourceId, "PRIMARY"),
                OrderFixtures.OWNER_ID,
                OrderFixtures.FACILITY_ID,
                OrderFixtures.LOCATION_ID,
                REQUIRED_BY,
                50,
                enqueuedAt,
                lines,
                () -> uuid(lineIds.getAndIncrement()));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(
            basePackageClasses = {
                JpaAllocationDemandRepository.class,
                JpaAllocationCancellationOperationRepository.class
            })
    static class RepositoryConfiguration {}
}
