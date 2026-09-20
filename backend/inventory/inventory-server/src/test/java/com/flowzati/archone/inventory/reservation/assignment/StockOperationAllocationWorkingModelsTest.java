package com.flowzati.archone.inventory.reservation.assignment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.foundation.error.StaleStateException;
import com.flowzati.archone.inventory.allocation.application.error.StockAllocationErrorCode;
import com.flowzati.archone.inventory.allocation.application.state.MoveQuantAllocationSet;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.application.state.StockOperationComposite;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOperationAllocationWorkingModelsTest {

    private static final UUID OPERATION_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);
    private static final UUID DESTINATION_ID = uuid(4);
    private static final UUID MOVE_A = uuid(11);
    private static final UUID MOVE_B = uuid(12);
    private static final UUID QUANT_A = uuid(21);
    private static final UUID QUANT_B = uuid(22);
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-27");

    @Test
    void oneCompositeOwnsHomogeneousStateCoverageAndSnapshotRules() {
        StockOperationComposite operationComposite = assignedOperation();

        assertThatCode(() -> operationComposite.requireHomogeneous(
                        StockOperationState.ASSIGNED, MoveState.ASSIGNED, "mixed state"))
                .doesNotThrowAnyException();
        assertThatCode(() -> operationComposite.requireExactCoverage("incomplete coverage"))
                .doesNotThrowAnyException();
        var snapshots = operationComposite.lifecycleSnapshot(NOW).moves();
        assertThat(snapshots).extracting(snapshot -> snapshot.moveId()).containsExactly(MOVE_A, MOVE_B);
        assertThat(snapshots.stream()
                        .flatMap(snapshot -> snapshot.moveLines().stream()
                                .map(line -> org.assertj.core.groups.Tuple.tuple(
                                        snapshot.moveId(), line.stockQuantId(), line.quantity()))))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_A, QUANT_A, 2),
                        org.assertj.core.groups.Tuple.tuple(MOVE_B, QUANT_B, 3));
    }

    @Test
    void incompleteOrForeignReservationDetailIsRejectedBeforeQuantWork() {
        StockOperation operation = operation(StockOperationState.ASSIGNED);
        List<StockMove> moves = List.of(move(MOVE_A, "SKU-A", 2, MoveState.ASSIGNED));

        StockOperationComposite incomplete = StockOperationComposite.of(operation, moves, List.of());
        assertThatThrownBy(() -> incomplete.requireExactCoverage("incomplete coverage"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("incomplete coverage");

        assertThatThrownBy(() -> StockOperationComposite.of(
                                operation, moves, List.of(new StockMoveLine(uuid(30), uuid(99), QUANT_A, 2)))
                        .requireExactCoverage("foreign detail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("foreign detail");
    }

    @Test
    void allocationSetAggregatesQuantitiesValidatesScopeAndReturnsGlobalOrder() {
        StockOperationComposite operationComposite = assignedOperation();
        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromMoveLines(operationComposite.lines());
        StockQuant quantB = quant(QUANT_B, "SKU-B", OWNER_ID, LOCATION_ID, 10, 0);
        StockQuant quantA = quant(QUANT_A, "SKU-A", OWNER_ID, LOCATION_ID, 10, 0);

        assertThat(allocationSet.quantityForStockQuant(QUANT_A)).isEqualTo(2);
        assertThat(allocationSet.quantityForStockQuant(QUANT_B)).isEqualTo(3);
        assertThat(allocationSet.validateAndOrder(List.of(quantB, quantA), operationComposite, TODAY, true))
                .extracting(StockQuant::getId)
                .containsExactly(QUANT_A, QUANT_B);
    }

    @Test
    void quantScopeOrAtpMismatchRejectsTheCompleteTargetSet() {
        StockOperationComposite operationComposite = assignedOperation();
        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromMoveLines(operationComposite.lines());
        StockQuant foreign = quant(QUANT_A, "SKU-A", uuid(90), LOCATION_ID, 10, 0);
        StockQuant quantB = quant(QUANT_B, "SKU-B", OWNER_ID, LOCATION_ID, 10, 0);

        assertThatThrownBy(
                        () -> allocationSet.validateAndOrder(List.of(foreign, quantB), operationComposite, TODAY, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scopes do not match");

        StockQuant insufficient = quant(QUANT_A, "SKU-A", OWNER_ID, LOCATION_ID, 10, 9);
        assertThatThrownBy(() ->
                        allocationSet.validateAndOrder(List.of(insufficient, quantB), operationComposite, TODAY, true))
                .isInstanceOfSatisfying(
                        StaleStateException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(StockAllocationErrorCode.ALLOCATION_SET_STALE))
                .hasMessageContaining("available-to-promise");
    }

    private static StockOperationComposite assignedOperation() {
        StockOperation operation = operation(StockOperationState.ASSIGNED);
        List<StockMove> moves =
                List.of(move(MOVE_A, "SKU-A", 2, MoveState.ASSIGNED), move(MOVE_B, "SKU-B", 3, MoveState.ASSIGNED));
        List<StockMoveLine> lines = List.of(
                new StockMoveLine(uuid(31), MOVE_A, QUANT_A, 2), new StockMoveLine(uuid(32), MOVE_B, QUANT_B, 3));
        return StockOperationComposite.of(operation, moves, lines);
    }

    private static StockOperation operation(StockOperationState state) {
        return new StockOperation(
                OPERATION_ID,
                uuid(5),
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                StockOperationSource.primaryOrder(uuid(6).toString()),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                50,
                state,
                0L);
    }

    private static StockMove move(UUID id, String skuCode, int quantity, MoveState state) {
        return new StockMove(
                id,
                OPERATION_ID,
                OWNER_ID,
                skuCode,
                LOCATION_ID,
                DESTINATION_ID,
                id.toString(),
                id.equals(MOVE_A) ? 1 : 2,
                quantity,
                state,
                NOW.minusSeconds(60),
                state == MoveState.CONFIRMED ? null : NOW,
                0L);
    }

    private static StockQuant quant(
            UUID id, String skuCode, UUID ownerId, UUID locationId, int onHandQuantity, int reservedQuantity) {
        return new StockQuant(
                id,
                ownerId,
                locationId,
                skuCode,
                TODAY.minusDays(10),
                TODAY.plusDays(10),
                onHandQuantity,
                reservedQuantity,
                0L);
    }

    private static UUID uuid(int value) {
        return new UUID(0, value);
    }
}
