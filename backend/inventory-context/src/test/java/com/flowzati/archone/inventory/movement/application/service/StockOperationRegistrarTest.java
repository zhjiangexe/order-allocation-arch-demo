package com.flowzati.archone.inventory.movement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.exception.SourceMovementConflictException;
import com.flowzati.archone.inventory.movement.application.invocation.MovementLine;
import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Stock movement group registration")
class StockOperationRegistrarTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID MOVE_A_ID = uuid(2);
    private static final UUID MOVE_B_ID = uuid(3);
    private static final UUID STOCK_OPERATION_TYPE_ID = uuid(4);
    private static final UUID OWNER_ID = uuid(5);
    private static final UUID FROM_LOCATION_ID = uuid(6);
    private static final UUID TO_LOCATION_ID = uuid(7);
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-27T01:00:00.123456789Z");
    private static final Instant DISPATCH_BY = Instant.parse("2026-08-28T01:00:00.987654321Z");

    @Mock
    private StockOperationStore stockOperationStore;

    @Mock
    private StockMoveStore stockMoveStore;

    private Queue<UUID> ids;
    private StockOperationRegistrar registrar;

    @BeforeEach
    void setUp() {
        ids = new ArrayDeque<>(List.of(STOCK_OPERATION_ID, MOVE_A_ID, MOVE_B_ID, uuid(8), uuid(9)));
        registrar = new StockOperationRegistrar(stockOperationStore, stockMoveStore, ids::remove);
    }

    @Test
    @DisplayName("first registration creates one confirmed operation and canonical confirmed moves")
    void createsCanonicalConfirmedMovementGroup() {
        RegisterStockOperationCommand command = command(1);
        when(stockOperationStore.findBySource(command.source())).thenReturn(Optional.empty());
        when(stockMoveStore.saveAll(anyCollection())).thenAnswer(invocation -> List.copyOf(invocation.getArgument(0)));

        var result = registrar.register(command);

        assertThat(result.created()).isTrue();
        assertThat(result.operation().state()).isEqualTo(StockOperationState.CONFIRMED);
        assertThat(result.operation().source()).isEqualTo(command.source());
        assertThat(result.operation().enqueuedAt()).isEqualTo(Instant.parse("2026-08-27T01:00:00.123456Z"));
        assertThat(result.moves())
                .extracting(
                        StockMove::getSourceLineId,
                        StockMove::getLineSequence,
                        StockMove::getDemandQuantity,
                        StockMove::getState)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LINE-A", 1, 1, MoveState.CONFIRMED),
                        org.assertj.core.groups.Tuple.tuple("LINE-B", 2, 2, MoveState.CONFIRMED));

        ArgumentCaptor<StockOperation> operation = ArgumentCaptor.forClass(StockOperation.class);
        verify(stockOperationStore).save(operation.capture());
        assertThat(operation.getValue().id()).isEqualTo(STOCK_OPERATION_ID);
    }

    @Test
    @DisplayName("equal source replay returns the existing group even after lifecycle state advances")
    void replaysEqualImmutableContent() {
        RegisterStockOperationCommand command = command(1);
        StockOperation existing = existingPicking(command);
        List<StockMove> existingMoves = existingMoves(command, 1);
        existingMoves.forEach(move -> move.assign(ENQUEUED_AT.plusSeconds(1)));
        existing.assign();
        when(stockOperationStore.findBySource(command.source())).thenReturn(Optional.of(existing));
        when(stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID)).thenReturn(existingMoves);

        var result = registrar.register(command);

        assertThat(result.created()).isFalse();
        assertThat(result.operation()).isSameAs(existing);
        assertThat(result.moves()).containsExactlyElementsOf(existingMoves);
        verify(stockOperationStore, never()).save(org.mockito.ArgumentMatchers.any());
        verify(stockMoveStore, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("same source identity with quantity drift is rejected without partial writes")
    void rejectsSourceContentDrift() {
        RegisterStockOperationCommand accepted = command(1);
        RegisterStockOperationCommand drifted = command(9);
        when(stockOperationStore.findBySource(drifted.source())).thenReturn(Optional.of(existingPicking(accepted)));
        when(stockMoveStore.findOrderedByStockOperationId(STOCK_OPERATION_ID)).thenReturn(existingMoves(accepted, 1));

        assertThatThrownBy(() -> registrar.register(drifted)).isInstanceOf(SourceMovementConflictException.class);

        verify(stockOperationStore, never()).save(org.mockito.ArgumentMatchers.any());
        verify(stockMoveStore, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("checked SKU aggregation rejects integer overflow before persistence")
    void rejectsAggregatedQuantityOverflow() {
        assertThatThrownBy(() -> new RegisterStockOperationCommand(
                        STOCK_OPERATION_TYPE_ID,
                        StockOperationDirection.OUTBOUND,
                        StockOperationSource.primaryOrder("ORDER-OVERFLOW"),
                        OWNER_ID,
                        FROM_LOCATION_ID,
                        TO_LOCATION_ID,
                        MovementAssignmentPolicy.SHIP_COMPLETE,
                        ENQUEUED_AT,
                        DISPATCH_BY,
                        50,
                        List.of(
                                new MovementLine("LINE-A", "SKU-A", Integer.MAX_VALUE),
                                new MovementLine("LINE-B", "SKU-A", 1))))
                .isInstanceOf(ArithmeticException.class);

        verifyNoInteractions(stockOperationStore, stockMoveStore);
    }

    private static RegisterStockOperationCommand command(int lineAQuantity) {
        return new RegisterStockOperationCommand(
                STOCK_OPERATION_TYPE_ID,
                StockOperationDirection.OUTBOUND,
                StockOperationSource.primaryOrder("ORDER-1"),
                OWNER_ID,
                FROM_LOCATION_ID,
                TO_LOCATION_ID,
                MovementAssignmentPolicy.SHIP_COMPLETE,
                ENQUEUED_AT,
                DISPATCH_BY,
                50,
                List.of(new MovementLine("LINE-B", "SKU-B", 2), new MovementLine("LINE-A", "SKU-A", lineAQuantity)));
    }

    private static StockOperation existingPicking(RegisterStockOperationCommand command) {
        return StockOperation.confirmedStockConsumption(
                STOCK_OPERATION_ID,
                command.stockOperationTypeId(),
                command.direction(),
                command.ownerId(),
                command.source(),
                command.fromLocationId(),
                command.toLocationId(),
                command.assignmentPolicy(),
                ENQUEUED_AT.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                DISPATCH_BY.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                command.releasePriority());
    }

    private static List<StockMove> existingMoves(RegisterStockOperationCommand command, int lineAQuantity) {
        return List.of(
                StockMove.confirmedForSourceLine(
                        MOVE_A_ID,
                        STOCK_OPERATION_ID,
                        OWNER_ID,
                        "SKU-A",
                        FROM_LOCATION_ID,
                        TO_LOCATION_ID,
                        "LINE-A",
                        1,
                        lineAQuantity,
                        ENQUEUED_AT.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
                StockMove.confirmedForSourceLine(
                        MOVE_B_ID,
                        STOCK_OPERATION_ID,
                        OWNER_ID,
                        "SKU-B",
                        FROM_LOCATION_ID,
                        TO_LOCATION_ID,
                        "LINE-B",
                        2,
                        2,
                        ENQUEUED_AT.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
