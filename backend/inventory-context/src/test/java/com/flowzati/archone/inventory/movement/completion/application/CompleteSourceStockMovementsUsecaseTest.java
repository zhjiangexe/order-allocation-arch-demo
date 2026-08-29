package com.flowzati.archone.inventory.movement.completion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.command.CompleteSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteSourceStockMovementsUsecase;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteStockOperationUsecase;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompleteSourceStockMovementsUsecaseTest {

    private static final UUID STOCK_OPERATION_ID = new UUID(0, 1);
    private static final UUID MOVE_A_ID = new UUID(0, 2);
    private static final UUID MOVE_B_ID = new UUID(0, 3);
    private static final UUID ORDER_ID = new UUID(0, 4);
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-27T10:00:00Z");
    private static final StockOperationSource SOURCE = StockOperationSource.primaryOrder(ORDER_ID.toString());

    private StockMoveStore stockMoveStore;
    private StockOperationStore stockOperationStore;
    private CompleteStockOperationUsecase completeOperation;
    private CompleteSourceStockMovementsUsecase usecase;

    @BeforeEach
    void setUp() {
        stockMoveStore = mock(StockMoveStore.class);
        stockOperationStore = mock(StockOperationStore.class);
        completeOperation = mock(CompleteStockOperationUsecase.class);
        usecase = new CompleteSourceStockMovementsUsecase(stockMoveStore, stockOperationStore, completeOperation);
    }

    @Test
    void resolvesCompleteSourceProofToOnePickingTarget() {
        StockMove moveA = move(MOVE_A_ID, "line-a", 1);
        StockMove moveB = move(MOVE_B_ID, "line-b", 2);
        when(stockMoveStore.findByIds(List.of(MOVE_A_ID, MOVE_B_ID))).thenReturn(List.of(moveA, moveB));
        when(stockOperationStore.findById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation()));
        when(stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID)).thenReturn(List.of(moveA, moveB));
        when(completeOperation.execute(STOCK_OPERATION_ID, COMPLETED_AT)).thenReturn(true);

        assertThat(usecase.execute(command(List.of(MOVE_A_ID, MOVE_B_ID)))).satisfies(result -> {
            assertThat(result.stockOperationId()).isEqualTo(STOCK_OPERATION_ID);
            assertThat(result.changed()).isTrue();
        });

        verify(completeOperation).execute(STOCK_OPERATION_ID, COMPLETED_AT);
    }

    @Test
    void rejectsPartialPickingProofBeforePhysicalCompletion() {
        StockMove moveA = move(MOVE_A_ID, "line-a", 1);
        StockMove moveB = move(MOVE_B_ID, "line-b", 2);
        when(stockMoveStore.findByIds(List.of(MOVE_A_ID))).thenReturn(List.of(moveA));
        when(stockOperationStore.findById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation()));
        when(stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID)).thenReturn(List.of(moveA, moveB));

        assertThatThrownBy(() -> usecase.execute(command(List.of(MOVE_A_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every movement");
    }

    @Test
    void rejectsACompletionProofFromAnotherSource() {
        StockMove move = move(MOVE_A_ID, "line-a", 1);
        when(stockMoveStore.findByIds(List.of(MOVE_A_ID))).thenReturn(List.of(move));
        when(stockOperationStore.findById(STOCK_OPERATION_ID)).thenReturn(Optional.of(operation()));

        var otherSource = StockOperationSource.primaryOrder(new UUID(0, 99).toString());
        assertThatThrownBy(() -> usecase.execute(
                        new CompleteSourceStockMovementsCommand(otherSource, List.of(MOVE_A_ID), COMPLETED_AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    private static CompleteSourceStockMovementsCommand command(List<UUID> moveIds) {
        return new CompleteSourceStockMovementsCommand(SOURCE, moveIds, COMPLETED_AT);
    }

    private static StockOperation operation() {
        return new StockOperation(
                STOCK_OPERATION_ID,
                new UUID(0, 5),
                StockOperationDirection.OUTBOUND,
                new UUID(0, 6),
                new UUID(0, 7),
                new UUID(0, 8),
                SOURCE,
                MovementAssignmentPolicy.SHIP_COMPLETE,
                COMPLETED_AT.minusSeconds(120),
                COMPLETED_AT.plusSeconds(3600),
                50,
                StockOperationState.ASSIGNED,
                0L);
    }

    private static StockMove move(UUID moveId, String sourceLineId, int sequence) {
        return new StockMove(
                moveId,
                STOCK_OPERATION_ID,
                new UUID(0, 6),
                "SKU-A",
                new UUID(0, 7),
                new UUID(0, 8),
                sourceLineId,
                sequence,
                1,
                MoveState.ASSIGNED,
                COMPLETED_AT.minusSeconds(120),
                COMPLETED_AT.minusSeconds(60),
                0L);
    }
}
