package com.flowzati.archone.inventory.allocation.planning.testsupport;

import com.flowzati.archone.inventory.allocation.domain.StockMoveDemand;
import com.flowzati.archone.inventory.allocation.domain.StockOperationDemand;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.util.Collection;
import java.util.List;

/** Copies canonical confirmed movement aggregates into the immutable allocation demand boundary. */
public final class StockOperationDemandFactory {

    public static StockOperationDemand from(StockOperation operation, Collection<StockMove> moves) {
        if (operation == null || moves == null || operation.state() != StockOperationState.CONFIRMED) {
            throw new IllegalArgumentException("Planning requires one confirmed operation and its moves");
        }
        List<StockMoveDemand> moveDemands =
                moves.stream().map(move -> toMoveDemand(operation, move)).toList();
        return new StockOperationDemand(
                operation.id(),
                operation.version(),
                operation.ownerId(),
                operation.fromLocationId(),
                operation.assignmentPolicy(),
                moveDemands);
    }

    private static StockMoveDemand toMoveDemand(StockOperation operation, StockMove move) {
        if (!operation.id().equals(move.getStockOperationId())
                || !operation.ownerId().equals(move.getOwnerId())
                || !operation.fromLocationId().equals(move.getFromLocationId())
                || move.getState() != MoveState.CONFIRMED
                || move.getSourceLineId() == null
                || move.getLineSequence() == null) {
            throw new IllegalArgumentException("Stock move does not belong to the confirmed demand group");
        }
        return new StockMoveDemand(
                move.getId(),
                move.getVersion(),
                move.getSourceLineId(),
                move.getLineSequence(),
                move.getSkuCode(),
                move.getDemandQuantity());
    }

    private StockOperationDemandFactory() {}
}
