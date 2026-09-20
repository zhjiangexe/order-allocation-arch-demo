package com.flowzati.archone.inventory.allocation.application.result;

import java.util.List;
import java.util.UUID;

/** One committed Stock Move and the move lines that exactly cover it. */
public record AssignedMove(
        UUID moveId,
        String sourceLineId,
        int lineSequence,
        String skuCode,
        int quantity,
        List<AssignedMoveLine> moveLines) {

    public AssignedMove {
        if (moveId == null
                || sourceLineId == null
                || sourceLineId.isBlank()
                || lineSequence <= 0
                || skuCode == null
                || skuCode.isBlank()
                || quantity <= 0
                || moveLines == null
                || moveLines.isEmpty()) {
            throw new IllegalArgumentException("Assigned move requires source trace, SKU, quantity and move lines");
        }
        moveLines = List.copyOf(moveLines);
        int covered;
        try {
            covered = moveLines.stream().mapToInt(AssignedMoveLine::quantity).reduce(0, Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Assigned move-line quantity exceeds integer range", overflow);
        }
        if (covered != quantity) {
            throw new IllegalArgumentException("Assigned move lines must exactly cover the move");
        }
    }
}
