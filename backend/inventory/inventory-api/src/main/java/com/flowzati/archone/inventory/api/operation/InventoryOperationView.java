package com.flowzati.archone.inventory.api.operation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InventoryOperationView(Source source, Operation operation, List<Move> moves) {

    public InventoryOperationView {
        moves = List.copyOf(moves);
    }

    public record Source(String type, String sourceId, String operationUnitKey) {}

    public record Operation(
            UUID stockOperationId,
            UUID stockOperationTypeId,
            String direction,
            UUID ownerId,
            UUID fromLocationId,
            UUID toLocationId,
            String assignmentPolicy,
            Instant enqueuedAt,
            Instant dispatchBy,
            Integer releasePriority,
            String state) {}

    public record Move(
            UUID moveId,
            String sourceLineId,
            Integer lineSequence,
            String skuCode,
            int quantity,
            String state,
            Instant createdAt,
            Instant assignedAt,
            List<Batch> batches) {

        public Move {
            batches = List.copyOf(batches);
        }
    }

    public record Batch(
            UUID stockQuantId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate, int quantity) {}
}
