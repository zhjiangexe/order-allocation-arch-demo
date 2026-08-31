package com.flowzati.archone.inventory.allocation.application.event;

import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application event stating that a Stock Operation was durably assigned to concrete Stock Quants. */
public record StockOperationAssigned(
        UUID stockOperationId,
        UUID stockOperationTypeId,
        UUID facilityId,
        StockOperationSource source,
        UUID ownerId,
        UUID sourceLocationId,
        UUID destinationLocationId,
        Instant dispatchBy,
        int releasePriority,
        Instant assignedAt,
        List<Move> moves) {

    public StockOperationAssigned {
        Objects.requireNonNull(stockOperationId, "Assigned stock operation ID is required");
        Objects.requireNonNull(stockOperationTypeId, "Assigned stock operation type is required");
        Objects.requireNonNull(facilityId, "Assigned stock operation facility is required");
        Objects.requireNonNull(source, "Assigned stock operation source is required");
        Objects.requireNonNull(ownerId, "Assigned stock operation owner is required");
        Objects.requireNonNull(sourceLocationId, "Assigned stock operation source location is required");
        Objects.requireNonNull(destinationLocationId, "Assigned stock operation destination location is required");
        Objects.requireNonNull(dispatchBy, "Assigned stock operation dispatch time is required");
        Objects.requireNonNull(assignedAt, "Assigned stock operation occurrence time is required");
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Assigned stock operation requires a valid release priority");
        }
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("Assigned stock operation requires moves");
        }
        moves = List.copyOf(moves);
        HashSet<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> move == null || !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Assigned stock operation requires unique non-null moves");
        }
    }

    public static StockOperationAssigned from(StockOperationAssignmentResult result) {
        Objects.requireNonNull(result, "Stock operation assignment result is required");
        return new StockOperationAssigned(
                result.stockOperationId(),
                result.stockOperationTypeId(),
                result.facilityId(),
                result.source(),
                result.ownerId(),
                result.sourceLocationId(),
                result.destinationLocationId(),
                result.dispatchBy(),
                result.releasePriority(),
                result.assignedAt(),
                result.moves().stream()
                        .map(move -> new Move(
                                move.moveId(),
                                move.sourceLineId(),
                                move.skuCode(),
                                move.quantity(),
                                move.moveLines().stream()
                                        .map(line -> new MoveLine(line.stockQuantId(), line.quantity()))
                                        .toList()))
                        .toList());
    }

    /** One assigned Stock Move and its exact committed Move Lines. */
    public record Move(UUID moveId, String sourceLineId, String skuCode, int quantity, List<MoveLine> moveLines) {

        public Move {
            if (moveId == null
                    || sourceLineId == null
                    || sourceLineId.isBlank()
                    || skuCode == null
                    || skuCode.isBlank()
                    || quantity <= 0
                    || moveLines == null
                    || moveLines.isEmpty()) {
                throw new IllegalArgumentException(
                        "Assigned stock operation move requires source trace, SKU, quantity and move lines");
            }
            moveLines = List.copyOf(moveLines);
            int covered;
            try {
                covered = moveLines.stream().mapToInt(MoveLine::quantity).reduce(0, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Assigned move-line quantity exceeds integer range", overflow);
            }
            if (covered != quantity) {
                throw new IllegalArgumentException("Assigned move lines must exactly cover the move");
            }
        }
    }

    /** One committed Stock Move-to-Quant quantity carried by the assignment event. */
    public record MoveLine(UUID stockQuantId, int quantity) {

        public MoveLine {
            if (stockQuantId == null || quantity <= 0) {
                throw new IllegalArgumentException("Assigned move line requires stock quant and positive quantity");
            }
        }
    }
}
