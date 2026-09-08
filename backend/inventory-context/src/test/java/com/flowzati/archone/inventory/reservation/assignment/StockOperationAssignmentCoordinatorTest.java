package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.result.AssignedMove;
import com.flowzati.archone.inventory.allocation.application.result.AssignedMoveLine;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.store.OwnerAllocationPolicyStore;
import com.flowzati.archone.inventory.allocation.application.store.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.allocation.domain.service.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.valueobject.ProposedMoveLine;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SkuQuantities;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockQuantSupply;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
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
    private final OwnerAllocationPolicyStore ownerAllocationPolicyStore = mock(OwnerAllocationPolicyStore.class);
    private StockOperationDemand demand;
    private StockAllocationSupply supply;

    @BeforeEach
    void setUp() {
        stockOperationAssignmentCandidateStore = mock(StockOperationAssignmentCandidateStore.class);
        stockAllocationSupplyStore = mock(StockAllocationSupplyStore.class);
        planner = mock(StockAllocationPlanner.class);
        allocationCommitter = mock(StockAllocationCommitter.class);
        coordinator = new StockOperationAssignmentCoordinator(
                stockOperationAssignmentCandidateStore,
                ownerAllocationPolicyStore,
                stockAllocationSupplyStore,
                planner,
                allocationCommitter,
                InventoryFixtures.businessClock(Clock.fixed(NOW, ZoneId.of("UTC")), "UTC"));
        when(ownerAllocationPolicyStore.find(OWNER_ID)).thenReturn(AllocationSequencePolicy.FIFO);
        demand = demand();
        supply =
                StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of(supply(QUANT_ID, "SKU-A", 3))));
    }

    @Test
    @DisplayName("initial assignment selects, plans and commits through one shared pipeline")
    void assignsReadyInitialOperationThroughSharedPipeline() {
        StockAllocationProposal proposal =
                StockAllocationProposal.ready(demand, List.of(new ProposedMoveLine(MOVE_ID, QUANT_ID, 2)));
        StockOperationAssignmentResult result = assignmentResult();
        when(stockOperationAssignmentCandidateStore.findDemand(STOCK_OPERATION_ID))
                .thenReturn(Optional.of(demand));
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(demand, supply)).thenReturn(proposal);
        when(allocationCommitter.commit(proposal, TODAY, NOW)).thenReturn(result);

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).containsSame(result);

        InOrder order = inOrder(
                stockOperationAssignmentCandidateStore,
                ownerAllocationPolicyStore,
                stockAllocationSupplyStore,
                planner,
                allocationCommitter);
        order.verify(stockOperationAssignmentCandidateStore).findDemand(STOCK_OPERATION_ID);
        order.verify(ownerAllocationPolicyStore).find(OWNER_ID);
        order.verify(stockOperationAssignmentCandidateStore).findPredecessor(demand, AllocationSequencePolicy.FIFO);
        order.verify(stockAllocationSupplyStore).findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY);
        order.verify(planner).plan(demand, supply);
        order.verify(allocationCommitter).commit(proposal, TODAY, NOW);
    }

    @Test
    @DisplayName("wake assignment uses the same planning and atomic assignment boundary")
    void assignsQueueHeadThroughTheSamePipeline() {
        AssignmentQueueKey queueKey = new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A");
        StockAllocationProposal proposal =
                StockAllocationProposal.ready(demand, List.of(new ProposedMoveLine(MOVE_ID, QUANT_ID, 2)));
        StockOperationAssignmentResult result = assignmentResult();
        when(stockOperationAssignmentCandidateStore.findNext(queueKey, AllocationSequencePolicy.FIFO))
                .thenReturn(Optional.of(demand));
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(demand, supply)).thenReturn(proposal);
        when(allocationCommitter.commit(proposal, TODAY, NOW)).thenReturn(result);

        assertThat(coordinator.tryAssignNext(queueKey)).containsSame(result);

        verify(allocationCommitter).commit(proposal, TODAY, NOW);
    }

    @Test
    @DisplayName("an earlier shared-SKU operation blocks before supply is read")
    void stopsAtPredecessorBeforeReadingSupply() {
        var predecessor = new StockOperationPredecessor(uuid(99), NOW.minusSeconds(1), Set.of("SKU-A"));
        when(stockOperationAssignmentCandidateStore.findDemand(STOCK_OPERATION_ID))
                .thenReturn(Optional.of(demand));
        when(stockOperationAssignmentCandidateStore.findPredecessor(demand, AllocationSequencePolicy.FIFO))
                .thenReturn(Optional.of(predecessor));

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).isEmpty();

        verifyNoInteractions(stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    void stopsQueueHeadAtPredecessorBeforeReadingSupply() {
        var queue = new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A");
        var predecessor = new StockOperationPredecessor(uuid(99), NOW.minusSeconds(1), Set.of("SKU-B"));
        when(stockOperationAssignmentCandidateStore.findNext(queue, AllocationSequencePolicy.FIFO))
                .thenReturn(Optional.of(demand));
        when(stockOperationAssignmentCandidateStore.findPredecessor(demand, AllocationSequencePolicy.FIFO))
                .thenReturn(Optional.of(predecessor));

        assertThat(coordinator.tryAssignNext(queue)).isEmpty();

        verifyNoInteractions(stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    void emptyQueueSkipsPredecessorAndPlanning() {
        var queue = new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A");

        assertThat(coordinator.tryAssignNext(queue)).isEmpty();

        verify(stockOperationAssignmentCandidateStore, never())
                .findPredecessor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    @DisplayName("SHIP_COMPLETE shortage emits no partial atomic assignment")
    void doesNotCommitAnInsufficientPlan() {
        StockAllocationProposal insufficient =
                StockAllocationProposal.insufficient(demand, SkuQuantities.of(Map.of("SKU-A", 1)));
        when(stockOperationAssignmentCandidateStore.findDemand(STOCK_OPERATION_ID))
                .thenReturn(Optional.of(demand));
        when(stockAllocationSupplyStore.findBySku(OWNER_ID, LOCATION_ID, Set.of("SKU-A"), TODAY))
                .thenReturn(supply);
        when(planner.plan(demand, supply)).thenReturn(insufficient);

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).isEmpty();

        verify(allocationCommitter, never())
                .commit(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsMissingDemandWithoutFurtherLookupsOrPlanning() {
        when(stockOperationAssignmentCandidateStore.findDemand(STOCK_OPERATION_ID))
                .thenReturn(Optional.empty());

        assertThat(coordinator.tryAssign(STOCK_OPERATION_ID)).isEmpty();

        verify(stockOperationAssignmentCandidateStore).findDemand(STOCK_OPERATION_ID);
        org.mockito.Mockito.verifyNoMoreInteractions(stockOperationAssignmentCandidateStore);
        verifyNoInteractions(ownerAllocationPolicyStore, stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    void queueHeadThatDisappearedBeforeProjectionIsNotAnError() {
        assertThat(coordinator.tryAssignNext(new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A")))
                .isEmpty();
        verifyNoInteractions(stockAllocationSupplyStore, planner, allocationCommitter);
    }

    @Test
    void retryAdviceMatchesTheActualCoordinatorAndOnlyTranslatesStaleProposals() {
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(coordinator);
        factory.addAspect(
                new com.flowzati.archone.inventory.allocation.infrastructure.config
                        .AssignmentRetryConflictTranslator());
        StockOperationAssignmentCoordinator proxy = factory.getProxy();
        var stale = new com.flowzati.archone.foundation.error.StaleStateException(
                com.flowzati.archone.inventory.allocation.application.error.StockAllocationErrorCode
                        .STOCK_ALLOCATION_PROPOSAL_STALE,
                "planned stock changed");
        when(stockOperationAssignmentCandidateStore.findDemand(STOCK_OPERATION_ID))
                .thenThrow(stale);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> proxy.tryAssign(STOCK_OPERATION_ID))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                .hasCause(stale);
        var queue = new AssignmentQueueKey(OWNER_ID, LOCATION_ID, "SKU-A");
        when(stockOperationAssignmentCandidateStore.findNext(queue, AllocationSequencePolicy.FIFO))
                .thenThrow(stale);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> proxy.tryAssignNext(queue))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                .hasCause(stale);
        var invalid = new IllegalArgumentException("invalid candidate");
        org.mockito.Mockito.doThrow(invalid)
                .when(stockOperationAssignmentCandidateStore)
                .findNext(queue, AllocationSequencePolicy.FIFO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> proxy.tryAssignNext(queue))
                .isSameAs(invalid);
    }

    private static StockOperationDemand demand() {
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
        return StockOperationDemandFactory.from(operation, List.of(move));
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
                List.of(new AssignedMove(
                        MOVE_ID, "LINE-1", 1, "SKU-A", 2, List.of(new AssignedMoveLine(QUANT_ID, 2)))));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
