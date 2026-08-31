package com.flowzati.archone.inventory.allocation.application.result;

import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
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
}
