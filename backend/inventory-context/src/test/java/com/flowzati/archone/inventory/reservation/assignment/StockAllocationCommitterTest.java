package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.StockOperationAssignmentCandidate;
import com.flowzati.archone.inventory.allocation.application.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.repo.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.ProposedMoveLine;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.reservation.application.messaging.StockOperationAssignmentPublisher;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.application.service.StockAllocationCommitter;
import com.flowzati.archone.inventory.reservation.application.service.StockOperationAssignmentResultFactory;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Stock allocation committer")
class StockAllocationCommitterTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);
    private static final UUID DESTINATION_ID = uuid(4);
    private static final UUID STOCK_OPERATION_TYPE_ID = uuid(5);
    private static final UUID FACILITY_ID = uuid(7);
    private static final UUID MOVE_1 = uuid(11);
    private static final UUID MOVE_2 = uuid(12);
    private static final UUID QUANT_1 = uuid(21);
    private static final UUID QUANT_2 = uuid(22);
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    private StockOperationStore stockOperationStore;
    private StockMoveStore stockMoveStore;
    private StockMoveLineStore stockMoveLineStore;
    private StockQuantStore stockQuantStore;
    private StockOperationTypeStore stockOperationTypeStore;
    private StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore;
    private StockOperationAssignmentPublisher assignmentPublisher;

    @BeforeEach
    void setUp() {
        stockOperationStore = mock(StockOperationStore.class);
        stockMoveStore = mock(StockMoveStore.class);
        stockMoveLineStore = mock(StockMoveLineStore.class);
        stockQuantStore = mock(StockQuantStore.class);
        stockOperationTypeStore = mock(StockOperationTypeStore.class);
        stockOperationAssignmentCandidateStore = mock(StockOperationAssignmentCandidateStore.class);
        assignmentPublisher = mock(StockOperationAssignmentPublisher.class);
        when(stockOperationTypeStore.findById(STOCK_OPERATION_TYPE_ID))
                .thenReturn(Optional.of(new StockOperationType(
                        STOCK_OPERATION_TYPE_ID,
                        FACILITY_ID,
                        StockOperationDirection.OUTBOUND,
                        "Outbound",
                        LOCATION_ID,
                        DESTINATION_ID)));
    }

    @Test
    @DisplayName("locks operation, moves and quants before assigning exact move lines and publishing V3")
    void assignsExistingMovesAtomically() {
        StockOperation operation = confirmedOperation();
        List<StockMove> moves = confirmedMoves();
        StockOperationDemand demand = StockOperationDemandFactory.from(operation, moves);
        StockAllocationProposal proposal = StockAllocationProposal.ready(
                demand,
                List.of(
                        new ProposedMoveLine(MOVE_1, QUANT_1, 2),
                        new ProposedMoveLine(MOVE_2, QUANT_1, 1),
                        new ProposedMoveLine(MOVE_2, QUANT_2, 3)));
        StockQuant first = quant(QUANT_1, 3);
        StockQuant second = quant(QUANT_2, 5);
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(moves);
        when(stockOperationAssignmentCandidateStore.findByOperationId(STOCK_OPERATION_ID))
                .thenReturn(new StockOperationAssignmentCandidate(demand, Optional.empty()));
        when(stockQuantStore.lockByIds(Set.of(QUANT_1, QUANT_2))).thenReturn(List.of(first, second));
        when(stockMoveStore.saveAll(moves)).thenReturn(moves);
        StockAllocationCommitter committer = committer(uuid(101), uuid(102), uuid(103));

        var result = committer.commit(proposal, TODAY, ASSIGNED_AT);

        assertThat(operation.state()).isEqualTo(StockOperationState.ASSIGNED);
        assertThat(moves).allMatch(move -> move.getState() == MoveState.ASSIGNED);
        assertThat(first.getReservedQuantity()).isEqualTo(3);
        assertThat(second.getReservedQuantity()).isEqualTo(3);
        assertThat(result.stockOperationId()).isEqualTo(STOCK_OPERATION_ID);
        assertThat(result.moves())
                .extracting(move -> move.moveId(), move -> move.quantity())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_1, 2), org.assertj.core.groups.Tuple.tuple(MOVE_2, 4));
        assertThat(result.moves().stream()
                        .flatMap(move -> move.moveLines().stream()
                                .map(line -> org.assertj.core.groups.Tuple.tuple(
                                        move.moveId(), line.stockQuantId(), line.quantity()))))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_1, QUANT_1, 2),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_1, 1),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_2, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<StockMoveLine>> linesCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stockMoveLineStore).saveAll(linesCaptor.capture());
        assertThat(linesCaptor.getValue())
                .extracting(StockMoveLine::moveId, StockMoveLine::stockQuantId, StockMoveLine::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_1, QUANT_1, 2),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_1, 1),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_2, 3));

        ArgumentCaptor<StockOperationAssignmentResult> publishedResult =
                ArgumentCaptor.forClass(StockOperationAssignmentResult.class);
        verify(assignmentPublisher).publish(publishedResult.capture());
        assertThat(publishedResult.getValue()).isEqualTo(result);

        var locks =
                inOrder(stockOperationStore, stockMoveStore, stockOperationAssignmentCandidateStore, stockQuantStore);
        locks.verify(stockOperationStore).lockById(STOCK_OPERATION_ID);
        locks.verify(stockMoveStore).lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID);
        locks.verify(stockOperationAssignmentCandidateStore).findByOperationId(STOCK_OPERATION_ID);
        locks.verify(stockQuantStore).lockByIds(Set.of(QUANT_1, QUANT_2));
    }

    @Test
    @DisplayName("an assigned retry reconstructs the committed result without reserving or publishing twice")
    void reconstructsAnIdempotentCommittedRetry() {
        StockAllocationProposal proposal = StockAllocationProposal.ready(
                StockOperationDemandFactory.from(confirmedOperation(), confirmedMoves()),
                List.of(new ProposedMoveLine(MOVE_1, QUANT_1, 2), new ProposedMoveLine(MOVE_2, QUANT_2, 4)));
        StockOperation assignedOperation = assignedOperation();
        List<StockMove> assignedMoves = assignedMoves();
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(assignedOperation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(assignedMoves);
        when(stockMoveLineStore.findByMoveIds(List.of(MOVE_1, MOVE_2)))
                .thenReturn(List.of(
                        new StockMoveLine(uuid(201), MOVE_1, QUANT_1, 2),
                        new StockMoveLine(uuid(202), MOVE_2, QUANT_2, 4)));

        var result = committer().commit(proposal, TODAY, ASSIGNED_AT.plusSeconds(30));

        assertThat(result.assignedAt()).isEqualTo(ASSIGNED_AT);
        assertThat(result.moves()).hasSize(2);
        verify(stockQuantStore, never()).lockByIds(any());
        verify(stockQuantStore, never()).save(any());
        verify(stockMoveLineStore, never()).saveAll(any());
        verify(assignmentPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("the final predecessor check rejects a stale eligible proposal before quant locking")
    void rejectsAProposalBlockedAfterPlanning() {
        StockOperation operation = confirmedOperation();
        List<StockMove> moves = confirmedMoves();
        StockOperationDemand demand = StockOperationDemandFactory.from(operation, moves);
        StockAllocationProposal proposal = StockAllocationProposal.ready(
                demand, List.of(new ProposedMoveLine(MOVE_1, QUANT_1, 2), new ProposedMoveLine(MOVE_2, QUANT_2, 4)));
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(moves);
        when(stockOperationAssignmentCandidateStore.findByOperationId(STOCK_OPERATION_ID))
                .thenReturn(new StockOperationAssignmentCandidate(
                        demand,
                        Optional.of(new StockOperationPredecessor(
                                uuid(99), ENQUEUED_AT.minusSeconds(1), Set.of("SKU-A")))));

        assertThatThrownBy(() -> committer().commit(proposal, TODAY, ASSIGNED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked by earlier shared-SKU operation");

        verify(stockQuantStore, never()).lockByIds(any());
    }

    private StockAllocationCommitter committer(UUID... lineIds) {
        Queue<UUID> ids = new ArrayDeque<>(List.of(lineIds));
        return new StockAllocationCommitter(
                stockOperationStore,
                stockMoveStore,
                stockMoveLineStore,
                stockQuantStore,
                stockOperationAssignmentCandidateStore,
                new StockOperationAssignmentResultFactory(stockOperationTypeStore),
                assignmentPublisher,
                () -> {
                    UUID id = ids.poll();
                    if (id == null) {
                        throw new AssertionError("Unexpected move-line ID request");
                    }
                    return id;
                });
    }

    private static StockOperation confirmedOperation() {
        return operation(StockOperationState.CONFIRMED, 0L);
    }

    private static StockOperation assignedOperation() {
        return operation(StockOperationState.ASSIGNED, 1L);
    }

    private static StockOperation operation(StockOperationState state, Long version) {
        return new StockOperation(
                STOCK_OPERATION_ID,
                STOCK_OPERATION_TYPE_ID,
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                new StockOperationSource(MovementSourceType.ORDER, uuid(6).toString(), "PRIMARY"),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                ENQUEUED_AT,
                ENQUEUED_AT.plusSeconds(3600),
                50,
                state,
                version);
    }

    private static List<StockMove> confirmedMoves() {
        return List.of(
                move(MOVE_1, uuid(61), 1, "SKU-A", 2, MoveState.CONFIRMED, null, 0L),
                move(MOVE_2, uuid(62), 2, "SKU-A", 4, MoveState.CONFIRMED, null, 0L));
    }

    private static List<StockMove> assignedMoves() {
        return List.of(
                move(MOVE_1, uuid(61), 1, "SKU-A", 2, MoveState.ASSIGNED, ASSIGNED_AT, 1L),
                move(MOVE_2, uuid(62), 2, "SKU-A", 4, MoveState.ASSIGNED, ASSIGNED_AT, 1L));
    }

    private static StockMove move(
            UUID moveId,
            UUID sourceLineId,
            int sequence,
            String skuCode,
            int quantity,
            MoveState state,
            Instant assignedAt,
            Long version) {
        return new StockMove(
                moveId,
                STOCK_OPERATION_ID,
                OWNER_ID,
                skuCode,
                LOCATION_ID,
                DESTINATION_ID,
                sourceLineId.toString(),
                sequence,
                quantity,
                state,
                ENQUEUED_AT,
                assignedAt,
                version);
    }

    private static StockQuant quant(UUID id, int quantity) {
        return new StockQuant(
                id,
                OWNER_ID,
                LOCATION_ID,
                "SKU-A",
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-12-31"),
                quantity,
                0,
                0L);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
