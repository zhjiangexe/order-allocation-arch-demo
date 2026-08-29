package com.flowzati.archone.inventory.movement.completion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecyclePublisher;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteStockOperationUsecase;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationLifecycleAction;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
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

@DisplayName("Complete operation transaction boundary")
class CompleteStockOperationUsecaseTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID MOVE_ID = uuid(2);
    private static final UUID QUANT_ID = uuid(3);
    private static final UUID OWNER_ID = uuid(4);
    private static final UUID LOCATION_ID = uuid(5);
    private static final UUID DESTINATION_ID = uuid(6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant ASSIGNED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant COMPLETED_AT = CREATED_AT.plusSeconds(120);

    private StockOperationStore stockOperationStore;
    private StockMoveStore stockMoveStore;
    private StockMoveLineStore stockMoveLineStore;
    private StockQuantStore stockQuantStore;
    private StockOperationLifecyclePublisher lifecyclePublisher;
    private CompleteStockOperationUsecase usecase;

    @BeforeEach
    void setUp() {
        stockOperationStore = mock(StockOperationStore.class);
        stockMoveStore = mock(StockMoveStore.class);
        stockMoveLineStore = mock(StockMoveLineStore.class);
        stockQuantStore = mock(StockQuantStore.class);
        lifecyclePublisher = mock(StockOperationLifecyclePublisher.class);
        usecase = new CompleteStockOperationUsecase(
                stockOperationStore, stockMoveStore, stockMoveLineStore, stockQuantStore, lifecyclePublisher);
    }

    @Test
    @DisplayName("consumes exact physical and reserved quantities while retaining execution detail")
    void completesAssignedMovementGroup() {
        StockOperation operation = operation(StockOperationState.ASSIGNED);
        StockMove move = move(MoveState.ASSIGNED);
        StockMoveLine line = new StockMoveLine(uuid(10), MOVE_ID, QUANT_ID, 3);
        StockQuant quant = quant(10, 3);
        given(operation, move, List.of(line));
        when(stockQuantStore.lockByIds(Set.of(QUANT_ID))).thenReturn(List.of(quant));

        assertThat(usecase.execute(STOCK_OPERATION_ID, COMPLETED_AT)).isTrue();

        assertThat(operation.state()).isEqualTo(StockOperationState.DONE);
        assertThat(move.getState()).isEqualTo(MoveState.DONE);
        assertThat(quant.getOnHandQuantity()).isEqualTo(7);
        assertThat(quant.getReservedQuantity()).isZero();
        verify(stockMoveStore).saveAll(List.of(move));
        verify(stockOperationStore).save(operation);
        ArgumentCaptor<StockOperationLifecycleSnapshot> snapshot =
                ArgumentCaptor.forClass(StockOperationLifecycleSnapshot.class);
        verify(lifecyclePublisher).publish(snapshot.capture(), eq(StockOperationLifecycleAction.COMPLETED));
        assertThat(snapshot.getValue().moves().getFirst().moveLines().getFirst().stockQuantId())
                .isEqualTo(QUANT_ID);
        var lockOrder = inOrder(stockOperationStore, stockMoveStore, stockMoveLineStore, stockQuantStore);
        lockOrder.verify(stockOperationStore).lockById(STOCK_OPERATION_ID);
        lockOrder.verify(stockMoveStore).lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID);
        lockOrder.verify(stockMoveLineStore).findByMoveIds(List.of(MOVE_ID));
        lockOrder.verify(stockQuantStore).lockByIds(Set.of(QUANT_ID));
    }

    @Test
    @DisplayName("completed group with retained exact detail is an idempotent replay")
    void replaysCompletedGroup() {
        given(
                operation(StockOperationState.DONE),
                move(MoveState.DONE),
                List.of(new StockMoveLine(uuid(10), MOVE_ID, QUANT_ID, 3)));

        assertThat(usecase.execute(STOCK_OPERATION_ID, COMPLETED_AT.plusSeconds(1)))
                .isFalse();

        verify(stockQuantStore, never()).lockByIds(any());
        verify(lifecyclePublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("incomplete execution evidence is rejected before physical stock changes")
    void rejectsIncompleteExecutionCoverage() {
        given(operation(StockOperationState.ASSIGNED), move(MoveState.ASSIGNED), List.of());

        assertThatThrownBy(() -> usecase.execute(STOCK_OPERATION_ID, COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly cover");

        verify(stockQuantStore, never()).lockByIds(org.mockito.ArgumentMatchers.any());
    }

    private void given(StockOperation operation, StockMove move, List<StockMoveLine> lines) {
        when(stockOperationStore.lockById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation));
        when(stockMoveStore.lockByStockOperationIdInIdOrder(STOCK_OPERATION_ID)).thenReturn(List.of(move));
        when(stockMoveLineStore.findByMoveIds(List.of(MOVE_ID))).thenReturn(lines);
    }

    private static StockOperation operation(StockOperationState state) {
        return new StockOperation(
                STOCK_OPERATION_ID,
                uuid(7),
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                StockOperationSource.primaryOrder(uuid(8).toString()),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                CREATED_AT,
                CREATED_AT.plusSeconds(3600),
                50,
                state,
                0L);
    }

    private static StockMove move(MoveState state) {
        return new StockMove(
                MOVE_ID,
                STOCK_OPERATION_ID,
                OWNER_ID,
                "SKU-A",
                LOCATION_ID,
                DESTINATION_ID,
                uuid(9).toString(),
                1,
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
