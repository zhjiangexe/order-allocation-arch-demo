package com.flowzati.archone.inventory.movement.application;

import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Source declaration, warehouse operation group and physical reservation detail in one read-only view. */
public record StockOperationView(SourceTrace source, Operation operation, List<Move> moves) {

    public StockOperationView {
        moves = List.copyOf(moves);
    }

    /** The application document that declared the stock-consuming operation. */
    public record SourceTrace(MovementSourceType type, String sourceId, String operationUnitKey) {}

    /** The warehouse work group; locations are movement endpoints, not source-document identity. */
    public record Operation(
            UUID stockOperationId,
            UUID stockOperationTypeId,
            StockOperationDirection direction,
            UUID ownerId,
            UUID fromLocationId,
            UUID toLocationId,
            MovementAssignmentPolicy assignmentPolicy,
            Instant enqueuedAt,
            Instant dispatchBy,
            Integer releasePriority,
            StockOperationState state) {}

    /** One source line's requested movement and the move lines currently assigned to it. */
    public record Move(
            UUID moveId,
            String sourceLineId,
            Integer lineSequence,
            String skuCode,
            int quantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt,
            List<MoveLineView> moveLines) {

        public Move {
            moveLines = List.copyOf(moveLines);
        }
    }

    /** Current move-line detail joined with quant attributes; released detail is absent by design. */
    public record MoveLineView(
            UUID stockQuantId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate, int quantity) {}
}
