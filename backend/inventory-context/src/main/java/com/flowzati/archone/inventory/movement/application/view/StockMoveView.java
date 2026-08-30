package com.flowzati.archone.inventory.movement.application.view;

import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One source line's requested movement and the move lines currently assigned to it. */
public record StockMoveView(
        UUID moveId,
        String sourceLineId,
        Integer lineSequence,
        String skuCode,
        int quantity,
        MoveState state,
        Instant createdAt,
        Instant assignedAt,
        List<StockMoveLineView> moveLines) {

    public StockMoveView {
        moveLines = List.copyOf(moveLines);
    }
}
