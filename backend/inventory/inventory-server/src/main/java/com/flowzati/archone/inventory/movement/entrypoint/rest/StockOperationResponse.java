package com.flowzati.archone.inventory.movement.entrypoint.rest;

import com.flowzati.archone.inventory.movement.application.result.StockMoveLineView;
import com.flowzati.archone.inventory.movement.application.result.StockMoveView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HTTP representation that owns transport field names independently from the application projection. */
public record StockOperationResponse(SourceTrace source, Operation operation, List<Move> moves) {

    public StockOperationResponse {
        moves = List.copyOf(moves);
    }

    public static StockOperationResponse from(StockOperationView view) {
        return new StockOperationResponse(
                new SourceTrace(
                        view.source().type(),
                        view.source().sourceId(),
                        view.source().operationUnitKey()),
                new Operation(
                        view.operation().stockOperationId(),
                        view.operation().stockOperationTypeId(),
                        view.operation().direction(),
                        view.operation().ownerId(),
                        view.operation().fromLocationId(),
                        view.operation().toLocationId(),
                        view.operation().assignmentPolicy(),
                        view.operation().enqueuedAt(),
                        view.operation().dispatchBy(),
                        view.operation().releasePriority(),
                        view.operation().state()),
                view.moves().stream().map(Move::from).toList());
    }

    public record SourceTrace(MovementSourceType type, String sourceId, String operationUnitKey) {}

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

    public record Move(
            UUID moveId,
            String sourceLineId,
            Integer lineSequence,
            String skuCode,
            int quantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt,
            List<Batch> batches) {

        public Move {
            batches = List.copyOf(batches);
        }

        private static Move from(StockMoveView move) {
            return new Move(
                    move.moveId(),
                    move.sourceLineId(),
                    move.lineSequence(),
                    move.skuCode(),
                    move.quantity(),
                    move.state(),
                    move.createdAt(),
                    move.assignedAt(),
                    move.moveLines().stream().map(Batch::from).toList());
        }
    }

    public record Batch(
            UUID stockQuantId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate, int quantity) {

        private static Batch from(StockMoveLineView moveLine) {
            return new Batch(
                    moveLine.stockQuantId(),
                    moveLine.locationId(),
                    moveLine.skuCode(),
                    moveLine.inDate(),
                    moveLine.expiryDate(),
                    moveLine.quantity());
        }
    }
}
