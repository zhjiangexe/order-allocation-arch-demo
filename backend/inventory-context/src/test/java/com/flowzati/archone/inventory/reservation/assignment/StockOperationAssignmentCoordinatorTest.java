package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.StockOperationAssignmentCandidate;
import com.flowzati.archone.inventory.allocation.application.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.repo.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.application.repo.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.ProposedMoveLine;
import com.flowzati.archone.inventory.allocation.domain.SkuQuantities;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.StockQuantSupply;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.reservation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("Shared stock-operation assignment")
class StockOperationAssignmentCoordinatorTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);
    private static final UUID DESTINATION_ID = uuid(4);
    private static final UUID MOVE_ID = uuid(5);
    private static final UUID QUANT_ID = uuid(6);
    private static final Instant NOW = Instant.parse("2026-08-27T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    private StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore;
    private StockAllocationSupplyStore stockAllocationSupplyStore;
    private StockAllocationPlanner planner;
    private StockAllocationCommitter allocationCommitter;
    private StockOperationAssignmentCoordinator coordinator;
    private StockOperationAssignmentCandidate candidate;
    private StockAllocationSupply supply;

    @BeforeEach
    void setUp() {
        stockOperationAssignmentCandidateStore = mock(StockOperationAssignmentCandidateStore.class);
        stockAllocationSupplyStore = mock(StockAllocationSupplyStore.class);
        planner = mock(StockAllocationPlanner.class);
        allocationCommitter = mock(StockAllocationCommitter.class);
        coordinator = new StockOperationAssignmentCoordinator(
                stockOperationAssignmentCandidateStore,
                stockAllocationSupplyStore,
                planner,
                allocationCommitter,
                InventoryFixtures.businessClock(Clock.fixed(NOW, ZoneId.of("UTC")), "UTC"));
        candidate = candidate(Optional.empty());
        supply =
                StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of(supply(QUANT_ID, "SKU-A", 3))));
    }

    @Test
    @DisplayName("initial assignment selects, plans and commits through one shared pipeline")
    void assignsReadyInitialOperationThroughSharedPipeline() {
        StockAllocationProposal proposal =
                StockAllocationProposal.ready(candidate.demand(), List.of(new ProposedMoveLine(MOVE_ID, QUANT_ID, 2)));
        StockOperationAssignmentResult result = assignmentResult();
        when(stockOperationAssignmentCandidateStore.findByOperationId(STOCK_OPERATION_ID))
                .thenReturn(candidate);
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(candidate.demand(), supply)).thenReturn(proposal);
        when(allocationCommitter.commit(proposal, TODAY, NOW)).thenReturn(result);

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).containsSame(result);

        InOrder order = inOrder(
                stockOperationAssignmentCandidateStore, stockAllocationSupplyStore, planner, allocationCommitter);
        order.verify(stockOperationAssignmentCandidateStore).findByOperationId(STOCK_OPERATION_ID);
        order.verify(stockAllocationSupplyStore).findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY);
        order.verify(planner).plan(candidate.demand(), supply);
        order.verify(allocationCommitter).commit(proposal, TODAY, NOW);
    }

    @Test
    @DisplayName("wake assignment uses the same planning and atomic assignment boundary")
    void assignsQueueHeadThroughTheSamePipeline() {
        AssignmentQueueKey queueKey = new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A");
        StockAllocationProposal proposal =
                StockAllocationProposal.ready(candidate.demand(), List.of(new ProposedMoveLine(MOVE_ID, QUANT_ID, 2)));
        StockOperationAssignmentResult result = assignmentResult();
        when(stockOperationAssignmentCandidateStore.findNext(queueKey)).thenReturn(Optional.of(candidate));
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(candidate.demand(), supply)).thenReturn(proposal);
        when(allocationCommitter.commit(proposal, TODAY, NOW)).thenReturn(result);

        assertThat(coordinator.tryAssignNext(queueKey)).containsSame(result);

        verify(allocationCommitter).commit(proposal, TODAY, NOW);
    }

    @Test
    @DisplayName("an earlier shared-SKU operation blocks before supply is read")
    void stopsAtPredecessorBeforeReadingSupply() {
        var blocked =
                candidate(Optional.of(new StockOperationPredecessor(uuid(99), NOW.minusSeconds(1), Set.of("SKU-A"))));
        when(stockOperationAssignmentCandidateStore.findByOperationId(STOCK_OPERATION_ID))
                .thenReturn(blocked);

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).isEmpty();

        verifyNoInteractions(stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    @DisplayName("SHIP_COMPLETE shortage emits no partial atomic assignment")
    void doesNotCommitAnInsufficientPlan() {
        StockAllocationProposal insufficient =
                StockAllocationProposal.insufficient(candidate.demand(), SkuQuantities.of(Map.of("SKU-A", 1)));
        when(stockOperationAssignmentCandidateStore.findByOperationId(STOCK_OPERATION_ID))
                .thenReturn(candidate);
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(candidate.demand(), supply)).thenReturn(insufficient);

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).isEmpty();

        verify(allocationCommitter, never())
                .commit(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static StockOperationAssignmentCandidate candidate(Optional<StockOperationPredecessor> predecessor) {
        StockOperation operation = new StockOperation(
                STOCK_OPERATION_ID,
                uuid(10),
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                StockOperationSource.primaryOrder("ORDER-1"),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                NOW,
                NOW.plusSeconds(3600),
                50,
                StockOperationState.CONFIRMED,
                0L);
        StockMove move = new StockMove(
                MOVE_ID,
                STOCK_OPERATION_ID,
                OWNER_ID,
                "SKU-A",
                LOCATION_ID,
                DESTINATION_ID,
                "LINE-1",
                1,
                2,
                MoveState.CONFIRMED,
                NOW,
                null,
                0L);
        return new StockOperationAssignmentCandidate(
                StockOperationDemandFactory.from(operation, List.of(move)), predecessor);
    }

    private static StockQuantSupply supply(UUID id, String skuCode, int availableToPromise) {
        return new StockQuantSupply(
                id, OWNER_ID, LOCATION_ID, skuCode, TODAY.minusDays(1), TODAY.plusDays(30), availableToPromise);
    }

    private static StockOperationAssignmentResult assignmentResult() {
        return new StockOperationAssignmentResult(
                STOCK_OPERATION_ID,
                uuid(10),
                uuid(11),
                StockOperationSource.primaryOrder("ORDER-1"),
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                MovementAssignmentPolicy.SHIP_COMPLETE,
                NOW.plusSeconds(3600),
                50,
                NOW,
                List.of(new StockOperationAssignmentResult.AssignedMove(
                        MOVE_ID,
                        "LINE-1",
                        1,
                        "SKU-A",
                        2,
                        List.of(new StockOperationAssignmentResult.AssignedMoveLine(QUANT_ID, 2)))));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
