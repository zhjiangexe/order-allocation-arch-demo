package com.flowzati.archone.inventory.movement.application.result;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.util.List;

/** Canonical operation group returned for both a first registration and an equal replay. */
public record StockOperationRegistrationResult(StockOperation operation, List<StockMove> moves, boolean created) {

    public StockOperationRegistrationResult {
        if (operation == null || moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("Registered operation and movements are required");
        }
        moves = List.copyOf(moves);
    }
}
