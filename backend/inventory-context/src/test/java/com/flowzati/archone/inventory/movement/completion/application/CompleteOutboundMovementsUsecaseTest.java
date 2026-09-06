package com.flowzati.archone.inventory.movement.completion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.port.StockOperationCompletedPublisher;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Complete outbound movements transaction boundary")
class CompleteOutboundMovementsUsecaseTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID MOVE_ID = uuid(2);
    private static final UUID SECOND_MOVE_ID = uuid(12);
    private static final UUID QUANT_ID = uuid(3);
    private static final UUID OWNER_ID = uuid(4);
    private static final UUID LOCATION_ID = uuid(5);
    private static final UUID DESTINATION_ID = uuid(6);
    private static final UUID ORDER_ID = uuid(8);
    private static final UUID SHIPMENT_ID = uuid(11);
    private static final Instant CREATED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant ASSIGNED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant COMPLETED_AT = CREATED_AT.plusSeconds(120);

    private StockOperationStore stockOperationStore;
    private StockMoveStore stockMoveStore;
    private StockMoveLineStore stockMoveLineStore;
    private StockQuantStore stockQuantStore;
    private StockOperationCompletedPublisher stockOperationCompletedPublisher;
    private CompleteOutboundMovementsUsecase usecase;

    @BeforeEach
    void setUp() {
        stockOperationStore = mock(StockOperationStore.class);
        stockMoveStore = mock(StockMoveStore.class);
        stockMoveLineStore = mock(StockMoveLineStore.class);
        stockQuantStore = mock(StockQuantStore.class);
        stockOperationCompletedPublisher = mock(StockOperationCompletedPublisher.class);
        usecase = new CompleteOutboundMovementsUsecase(
                stockOperationStore,
                stockMoveStore,
                stockMoveLineStore,
                stockQuantStore,
                stockOperationCompletedPublisher);
    }

    @Test
    @DisplayName("consumes stock and publishes one completed Stock Operation fact")
    void completesAssignedMovementGroup() {
        StockOperation operation = operation(StockOperationState.ASSIGNED, ORDER_ID);
        StockMove move = move(MOVE_ID, MoveState.ASSIGNED, 1);
        StockMoveLine line = new StockMoveLine(uuid(10), MOVE_ID, QUANT_ID, 3);
        StockQuant quant = quant(10, 3);
        given(operation, List.of(move), List.of(line), List.of(MOVE_ID));
        when(stockQuantStore.lockByIds(Set.of(QUANT_ID))).thenReturn(List.of(quant));

        assertThat(usecase.execute(command(null, List.of(MOVE_ID)))).isTrue();

        assertThat(operation.state()).isEqualTo(StockOperationState.DONE);
        assertThat(move.getState()).isEqualTo(MoveState.DONE);
        assertThat(quant.getOnHandQuantity()).isEqualTo(7);
        assertThat(quant.getReservedQuantity()).isZero();
        verify(stockMoveStore).saveAll(List.of(move));
        verify(stockOperationStore).save(operation);

        ArgumentCaptor<StockOperationCompleted> completedEvent = ArgumentCaptor.forClass(StockOperationCompleted.class);
        verify(stockOperationCompletedPublisher).publish(completedEvent.capture());
        assertThat(completedEvent
                        .getValue()
                        .snapshot()
                        .moves()
                        .getFirst()
                        .moveLines()
                        .getFirst()
                        .stockQuantId())
                .isEqualTo(QUANT_ID);
        assertThat(completedEvent.getValue().snapshot().stockOperationId()).isEqualTo(STOCK_OPERATION_ID);
        assertThat(completedEvent.getValue().snapshot().moves())
                .extracting("moveId")
                .containsExactly(MOVE_ID);
        assertThat(completedEvent.getValue().snapshot().occurredAt()).isEqualTo(COMPLETED_AT);
        assertThat(completedEvent.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(completedEvent.getValue().shipmentId()).isEqualTo(SHIPMENT_ID);

        var lockOrder = inOrder(stockOperationStore, stockMoveStore, stockMoveLineStore, stockQuantStore);
        lockOrder.verify(stockOperationStore).lockById(STOCK_OPERATION_ID);
        lockOrder.verify(stockMoveStore).lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID);
        lockOrder.verify(stockMoveLineStore).findByMoveIds(List.of(MOVE_ID));
        lockOrder.verify(stockQuantStore).lockByIds(Set.of(QUANT_ID));
    }

    @Test
    @DisplayName("completed group is an idempotent replay without duplicate publications")
    void replaysCompletedGroup() {
        StockMove move = move(MOVE_ID, MoveState.DONE, 1);
        given(
                operation(StockOperationState.DONE, ORDER_ID),
                List.of(move),
                List.of(new StockMoveLine(uuid(10), MOVE_ID, QUANT_ID, 3)),
                List.of(MOVE_ID));

        assertThat(usecase.execute(command(STOCK_OPERATION_ID, List.of(MOVE_ID))))
                .isFalse();

        verify(stockQuantStore, never()).lockByIds(any());
        verify(stockOperationCompletedPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("incomplete execution evidence is rejected before physical stock changes")
    void rejectsIncompleteExecutionCoverage() {
        StockMove move = move(MOVE_ID, MoveState.ASSIGNED, 1);
        given(operation(StockOperationState.ASSIGNED, ORDER_ID), List.of(move), List.of(), List.of(MOVE_ID));

        assertThatThrownBy(() -> usecase.execute(command(STOCK_OPERATION_ID, List.of(MOVE_ID))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly cover");

        verify(stockQuantStore, never()).lockByIds(any());
    }

    @Test
    @DisplayName("partial movement proof is rejected before execution detail is loaded")
    void rejectsPartialMovementProof() {
        StockMove firstMove = move(MOVE_ID, MoveState.ASSIGNED, 1);
        StockMove secondMove = move(SECOND_MOVE_ID, MoveState.ASSIGNED, 2);
        when(stockMoveStore.findByIds(List.of(MOVE_ID))).thenReturn(List.of(firstMove));
        when(stockOperationStore.lockById(STOCK_OPERATION_ID))
                .thenReturn(Optional.of(operation(StockOperationState.ASSIGNED, ORDER_ID)));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID))
                .thenReturn(List.of(firstMove, secondMove));

        assertThatThrownBy(() -> usecase.execute(command(STOCK_OPERATION_ID, List.of(MOVE_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every movement");

        verify(stockMoveLineStore, never()).findByMoveIds(any());
    }

    @Test
    @DisplayName("movement proof from another order source is rejected")
    void rejectsAnotherOrderSource() {
        StockMove move = move(MOVE_ID, MoveState.ASSIGNED, 1);
        given(operation(StockOperationState.ASSIGNED, uuid(99)), List.of(move), List.of(), List.of(MOVE_ID));

        assertThatThrownBy(() -> usecase.execute(command(STOCK_OPERATION_ID, List.of(MOVE_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    @DisplayName("canonical operation must match a supplied modern handover identity")
    void rejectsMismatchedExpectedOperation() {
        StockMove move = move(MOVE_ID, MoveState.ASSIGNED, 1);
        when(stockMoveStore.findByIds(List.of(MOVE_ID))).thenReturn(List.of(move));

        assertThatThrownBy(() -> usecase.execute(command(uuid(99), List.of(MOVE_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(stockOperationStore, never()).lockById(any());
    }

    private void given(
            StockOperation operation, List<StockMove> moves, List<StockMoveLine> lines, List<UUID> requestedIds) {
        when(stockMoveStore.findByIds(requestedIds))
                .thenReturn(moves.stream()
                        .filter(move -> requestedIds.contains(move.getId()))
                        .toList());
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(moves);
        when(stockMoveLineStore.findByMoveIds(
                        moves.stream().map(StockMove::getId).toList()))
                .thenReturn(lines);
    }

    private static CompleteOutboundMovementsCommand command(UUID expectedStockOperationId, List<UUID> movementIds) {
        return new CompleteOutboundMovementsCommand(
                ORDER_ID, SHIPMENT_ID, expectedStockOperationId, movementIds, COMPLETED_AT);
    }

    private static StockOperation operation(StockOperationState state, UUID orderId) {
        return new StockOperation(
                STOCK_OPERATION_ID,
                uuid(7),
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                StockOperationSource.primaryOrder(orderId.toString()),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                CREATED_AT,
                CREATED_AT.plusSeconds(3600),
                50,
                state,
                0L);
    }

    private static StockMove move(UUID moveId, MoveState state, int sequence) {
        return new StockMove(
                moveId,
                STOCK_OPERATION_ID,
                OWNER_ID,
                "SKU-A",
                LOCATION_ID,
                DESTINATION_ID,
                uuid(9 + sequence).toString(),
                sequence,
                3,
                state,
                CREATED_AT,
                ASSIGNED_AT,
                0L);
    }

    private static StockQuant quant(int onHand, int reserved) {
        return new StockQuant(
                QUANT_ID,
                OWNER_ID,
                LOCATION_ID,
                "SKU-A",
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-12-31"),
                onHand,
                reserved,
                0L);
    }

    private static UUID uuid(int value) {
        return new UUID(0, value);
    }
}
