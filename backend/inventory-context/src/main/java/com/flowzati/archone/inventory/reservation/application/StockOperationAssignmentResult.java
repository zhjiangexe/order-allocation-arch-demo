package com.flowzati.archone.inventory.reservation.application;

import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Canonical committed view reconstructed from a operation, its moves and current move lines. */
public record StockOperationAssignmentResult(
        UUID stockOperationId,
        UUID stockOperationTypeId,
        UUID facilityId,
        StockOperationSource source,
        UUID ownerId,
        UUID sourceLocationId,
        UUID destinationLocationId,
        MovementAssignmentPolicy policy,
        Instant dispatchBy,
        int releasePriority,
        Instant assignedAt,
        List<AssignedMove> moves) {

    public StockOperationAssignmentResult {
        if (stockOperationId == null
                || stockOperationTypeId == null
                || facilityId == null
                || source == null
                || ownerId == null
                || sourceLocationId == null
                || destinationLocationId == null
                || policy == null
                || dispatchBy == null
                || assignedAt == null
                || moves == null
                || moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "Stock operation assignment result requires operation, source, scope and moves");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Stock operation assignment result requires a valid release priority");
        }
        moves = List.copyOf(moves);
        HashSet<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Stock operation assignment result requires unique moves");
        }
    }

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
                covered =
                        moveLines.stream().mapToInt(AssignedMoveLine::quantity).reduce(0, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Assigned move-line quantity exceeds integer range", overflow);
            }
            if (covered != quantity) {
                throw new IllegalArgumentException("Assigned move lines must exactly cover the move");
            }
        }
    }

    public record AssignedMoveLine(UUID stockQuantId, int quantity) {

        public AssignedMoveLine {
            if (stockQuantId == null || quantity <= 0) {
                throw new IllegalArgumentException("Assigned move line requires stock quant and positive quantity");
            }
        }
    }
}
