package com.flowzati.archone.inventory.movement.cancellation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.allocation.application.usecase.ReleaseStockOperationUsecase;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleAction;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecycleChangedPublisher;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationPreparation;
import com.flowzati.archone.inventory.movement.application.result.StockOperationCancellationStatus;
import com.flowzati.archone.inventory.movement.application.service.StockOperationCancellationTransactions;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationCancellationStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockOperationCancellationTransactionsTest {

    private static final UUID STOCK_OPERATION_ID = new UUID(0, 1);
    private static final UUID MOVE_ID = new UUID(0, 2);
    private static final UUID QUANT_ID = new UUID(0, 3);
    private static final UUID OPERATION_ID = new UUID(0, 4);
    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");

    private StockOperationStore stockOperationStore;
    private StockMoveStore stockMoveStore;
    private StockMoveLineStore stockMoveLineStore;
    private StockOperationCancellationStore stockOperationCancellationStore;
    private ReleaseStockOperationUsecase releaseOperation;
    private StockOperationLifecycleChangedPublisher lifecyclePublisher;
    private StockOperationCancellationTransactions transactions;

    @BeforeEach
    void setUp() {
        stockOperationStore = mock(StockOperationStore.class);
        stockMoveStore = mock(StockMoveStore.class);
        stockMoveLineStore = mock(StockMoveLineStore.class);
        stockOperationCancellationStore = mock(StockOperationCancellationStore.class);
        releaseOperation = mock(ReleaseStockOperationUsecase.class);
        lifecyclePublisher = mock(StockOperationLifecycleChangedPublisher.class);
        transactions = new StockOperationCancellationTransactions(
                stockOperationStore,
                stockMoveStore,
                stockMoveLineStore,
                stockOperationCancellationStore,
                releaseOperation,
                lifecyclePublisher);
    }

    @Test
    void cancelsConfirmedGroupLocallyWithoutStartingWarehouseOperation() {
        StockOperation operation = operation(StockOperationState.CONFIRMED);
        StockMove move = move(MoveState.CONFIRMED);
        givenGroup(operation, move, List.of());

        StockOperationCancellationPreparation result = transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW);

        assertThat(result)
                .isEqualTo(
                        new StockOperationCancellationPreparation.Terminal(StockOperationCancellationStatus.COMPLETED));
        assertThat(operation.state()).isEqualTo(StockOperationState.CANCELLED);
        assertThat(move.getState()).isEqualTo(MoveState.CANCELLED);
        verify(stockOperationCancellationStore, never()).save(any());
        ArgumentCaptor<StockOperationLifecycleChanged> event =
                ArgumentCaptor.forClass(StockOperationLifecycleChanged.class);
        verify(lifecyclePublisher).publish(event.capture());
        assertThat(event.getValue().action()).isEqualTo(StockOperationLifecycleAction.CANCELLED);
        assertThat(event.getValue().snapshot().moves().getFirst().moveLines()).isEmpty();
    }

    @Test
    void assignedGroupStartsDurableOperationWithoutChangingStock() {
        StockOperation operation = operation(StockOperationState.ASSIGNED);
        StockMove move = move(MoveState.ASSIGNED);
        givenGroup(operation, move, List.of(new StockMoveLine(new UUID(0, 5), MOVE_ID, QUANT_ID, 3)));
        when(stockOperationCancellationStore.find(STOCK_OPERATION_ID, OPERATION_ID))
                .thenReturn(Optional.empty());
        when(stockOperationCancellationStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = (StockOperationCancellationPreparation.Continue)
                transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW);

        assertThat(result.checkpoint().state()).isEqualTo(StockOperationCancellationState.STARTED);
        assertThat(operation.state()).isEqualTo(StockOperationState.ASSIGNED);
        assertThat(move.getState()).isEqualTo(MoveState.ASSIGNED);
        verify(releaseOperation, never()).execute(any(), any());
        verify(lifecyclePublisher, never()).publish(any());
    }

    @Test
    void durableWarehouseConfirmationReleasesThenCancelsTheSamePicking() {
        StockOperationCancellation operation =
                StockOperationCancellation.start(STOCK_OPERATION_ID, OPERATION_ID, NOW.minusSeconds(2));
        operation.confirmExternally(NOW.minusSeconds(1));
        StockOperation assignedOperation = operation(StockOperationState.ASSIGNED);
        StockOperation confirmedOperation = operation(StockOperationState.CONFIRMED);
        StockMove assignedMove = move(MoveState.ASSIGNED);
        StockMove confirmedMove = move(MoveState.CONFIRMED);
        StockOperationLifecycleSnapshot snapshot = StockOperationLifecycleSnapshot.capture(
                assignedOperation,
                List.of(assignedMove),
                List.of(new StockMoveLine(new UUID(0, 5), MOVE_ID, QUANT_ID, 3)),
                NOW);
        when(stockOperationCancellationStore.find(STOCK_OPERATION_ID, OPERATION_ID))
                .thenReturn(Optional.of(operation));
        when(releaseOperation.releaseForCancellation(STOCK_OPERATION_ID, NOW)).thenReturn(Optional.of(snapshot));
        when(stockOperationStore.lockById(STOCK_OPERATION_ID))
                .thenReturn(Optional.of(assignedOperation), Optional.of(confirmedOperation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(List.of(confirmedMove));
        when(stockMoveLineStore.findByMoveIds(List.of(MOVE_ID))).thenReturn(List.of());

        assertThat(transactions.complete(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .isEqualTo(StockOperationCancellationStatus.COMPLETED);

        verify(releaseOperation).releaseForCancellation(STOCK_OPERATION_ID, NOW);
        assertThat(confirmedOperation.state()).isEqualTo(StockOperationState.CANCELLED);
        assertThat(confirmedMove.getState()).isEqualTo(MoveState.CANCELLED);
        assertThat(operation.state()).isEqualTo(StockOperationCancellationState.COMPLETED);
        verify(stockOperationCancellationStore).save(operation);
        ArgumentCaptor<StockOperationLifecycleChanged> publishedEvent =
                ArgumentCaptor.forClass(StockOperationLifecycleChanged.class);
        verify(lifecyclePublisher).publish(publishedEvent.capture());
        assertThat(publishedEvent.getValue().action()).isEqualTo(StockOperationLifecycleAction.CANCELLED);
        assertThat(publishedEvent
                        .getValue()
                        .snapshot()
                        .moves()
                        .getFirst()
                        .moveLines()
                        .getFirst()
                        .stockQuantId())
                .isEqualTo(QUANT_ID);
    }

    @Test
    void completedGroupIsNotCancellable() {
        StockOperation operation = operation(StockOperationState.DONE);
        StockMove move = move(MoveState.DONE);
        givenGroup(operation, move, List.of(new StockMoveLine(new UUID(0, 5), MOVE_ID, QUANT_ID, 3)));

        assertThat(transactions.prepare(STOCK_OPERATION_ID, OPERATION_ID, NOW))
                .isEqualTo(new StockOperationCancellationPreparation.Terminal(
                        StockOperationCancellationStatus.NOT_CANCELLABLE));

        verify(stockOperationCancellationStore, never()).save(any());
    }

    private void givenGroup(StockOperation operation, StockMove move, List<StockMoveLine> lines) {
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(List.of(move));
        when(stockMoveLineStore.findByMoveIds(List.of(MOVE_ID))).thenReturn(lines);
    }

    private static StockOperation operation(StockOperationState state) {
        return new StockOperation(
                STOCK_OPERATION_ID,
                new UUID(0, 6),
                StockOperationDirection.OUTBOUND,
                new UUID(0, 7),
                new UUID(0, 8),
                new UUID(0, 9),
                StockOperationSource.primaryOrder(new UUID(0, 10).toString()),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                50,
                state,
                0L);
    }

    private static StockMove move(MoveState state) {
        return new StockMove(
                MOVE_ID,
                STOCK_OPERATION_ID,
                new UUID(0, 7),
                "SKU-A",
                new UUID(0, 8),
                new UUID(0, 9),
                "line-1",
                1,
                3,
                state,
                NOW.minusSeconds(60),
                state == MoveState.CONFIRMED ? null : NOW.minusSeconds(30),
                0L);
    }
}
