package com.flowzati.archone.inventory.allocation.domain;

import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Immutable atomic demand copied from one confirmed operation and its confirmed moves. */
public record StockOperationDemand(
        UUID stockOperationId,
        Long stockOperationVersion,
        UUID ownerId,
        UUID fromLocationId,
        MovementAssignmentPolicy policy,
        List<StockMoveDemand> moves) {

    public StockOperationDemand {
        if (stockOperationId == null
                || stockOperationVersion == null
                || ownerId == null
                || fromLocationId == null
                || policy == null
                || moves == null
                || moves.isEmpty()) {
            throw new IllegalArgumentException("Stock operation demand requires operation, scope, policy and moves");
        }
        moves = moves.stream()
                .sorted(Comparator.comparingInt(StockMoveDemand::lineSequence))
                .toList();
        if (moves.stream().map(StockMoveDemand::moveId).distinct().count() != moves.size()) {
            throw new IllegalArgumentException("Stock operation demand contains duplicate moves");
        }
        if (moves.stream().map(StockMoveDemand::lineSequence).distinct().count() != moves.size()) {
            throw new IllegalArgumentException("Stock operation demand contains duplicate line sequences");
        }
    }

    public Set<String> skuCodes() {
        return moves.stream().map(StockMoveDemand::skuCode).collect(Collectors.toUnmodifiableSet());
    }

    public SkuQuantities requiredQuantities() {
        var quantities = new java.util.LinkedHashMap<String, Integer>();
        for (StockMoveDemand move : moves) {
            quantities.merge(move.skuCode(), move.quantity(), Math::addExact);
        }
        return SkuQuantities.of(quantities);
    }
}
