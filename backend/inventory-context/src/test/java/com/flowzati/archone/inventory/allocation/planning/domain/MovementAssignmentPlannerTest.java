package com.flowzati.archone.inventory.allocation.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.domain.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.domain.StockQuantSupply;
import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
import com.flowzati.archone.inventory.allocation.planning.testsupport.StockOperationDemandFactory;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Movement assignment planner")
class MovementAssignmentPlannerTest {

    private static final UUID STOCK_OPERATION_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);
    private static final UUID DESTINATION_ID = uuid(4);
    private static final UUID MOVE_1 = uuid(11);
    private static final UUID MOVE_2 = uuid(12);
    private static final UUID QUANT_1 = uuid(21);
    private static final UUID QUANT_2 = uuid(22);
    private static final Instant CREATED_AT = Instant.parse("2026-08-27T01:00:00Z");

    private final StockAllocationPlanner planner = new MovementAssignmentPlanner();

    @Test
    @DisplayName("repeated SKU moves use line sequence and FEFO deterministically without mutating quants")
    void plansRepeatedSkuByCanonicalMoveAndFefoOrder() {
        StockQuantSupply first = supply(QUANT_1, "SKU-A", LocalDate.parse("2026-09-01"), 3);
        StockQuantSupply second = supply(QUANT_2, "SKU-A", LocalDate.parse("2026-10-01"), 5);
        StockOperationDemand demand =
                snapshot(List.of(move(MOVE_2, "LINE-2", 2, "SKU-A", 4), move(MOVE_1, "LINE-1", 1, "SKU-A", 2)));
        StockAllocationSupply supplies =
                StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of(first, second)));

        var proposal = planner.plan(demand, supplies);
        var replay = planner.plan(demand, supplies);

        assertThat(proposal.isReady()).isTrue();
        assertThat(proposal.proposedMoveLines())
                .extracting(
                        reservation -> reservation.moveId(),
                        reservation -> reservation.stockQuantId(),
                        reservation -> reservation.quantity())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MOVE_1, QUANT_1, 2),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_1, 1),
                        org.assertj.core.groups.Tuple.tuple(MOVE_2, QUANT_2, 3));
        assertThat(replay.proposedMoveLines()).isEqualTo(proposal.proposedMoveLines());
        assertThat(supplies.forSku("SKU-A")).containsExactly(first, second);
    }

    @Test
    @DisplayName("any shortage reports every short SKU and emits no partial reservations")
    void reportsEveryShortSkuWithoutPartialReservations() {
        StockOperationDemand demand =
                snapshot(List.of(move(MOVE_1, "LINE-1", 1, "SKU-A", 3), move(MOVE_2, "LINE-2", 2, "SKU-B", 4)));
        StockAllocationSupply supplies = StockAllocationSupply.of(
                OWNER_ID,
                LOCATION_ID,
                Map.of(
                        "SKU-A", List.of(supply(QUANT_1, "SKU-A", LocalDate.parse("2026-09-01"), 1)),
                        "SKU-B", List.of(supply(QUANT_2, "SKU-B", LocalDate.parse("2026-09-01"), 1))));

        var proposal = planner.plan(demand, supplies);

        assertThat(proposal.isReady()).isFalse();
        assertThat(proposal.proposedMoveLines()).isEmpty();
        assertThat(proposal.missingQuantities().asMap()).containsAllEntriesOf(Map.of("SKU-A", 2, "SKU-B", 3));
    }

    @Test
    @DisplayName("an explicit empty SKU supply group reports the complete shortage")
    void reportsShortageFromAnExplicitEmptySupplyGroup() {
        StockOperationDemand demand = snapshot(List.of(move(MOVE_1, "LINE-1", 1, "SKU-A", 3)));
        StockAllocationSupply supplies = StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of()));

        var proposal = planner.plan(demand, supplies);

        assertThat(proposal.isReady()).isFalse();
        assertThat(proposal.proposedMoveLines()).isEmpty();
        assertThat(proposal.missingQuantities().asMap()).containsExactlyEntriesOf(Map.of("SKU-A", 3));
    }

    private static StockOperationDemand snapshot(List<StockMove> moves) {
        StockOperation operation = new StockOperation(
                STOCK_OPERATION_ID,
                uuid(5),
                StockOperationDirection.OUTBOUND,
                OWNER_ID,
                LOCATION_ID,
                DESTINATION_ID,
                StockOperationSource.primaryOrder("ORDER-1"),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                CREATED_AT,
                CREATED_AT.plusSeconds(3600),
                50,
                StockOperationState.CONFIRMED,
                0L);
        return StockOperationDemandFactory.from(operation, moves);
    }

    private static StockMove move(UUID moveId, String sourceLineId, int lineSequence, String skuCode, int quantity) {
        return new StockMove(
                moveId,
                STOCK_OPERATION_ID,
                OWNER_ID,
                skuCode,
                LOCATION_ID,
                DESTINATION_ID,
                sourceLineId,
                lineSequence,
                quantity,
                MoveState.CONFIRMED,
                CREATED_AT,
                null,
                0L);
    }

    private static StockQuantSupply supply(UUID id, String skuCode, LocalDate expiryDate, int availableToPromise) {
        return new StockQuantSupply(
                id, OWNER_ID, LOCATION_ID, skuCode, expiryDate.minusMonths(1), expiryDate, availableToPromise);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
