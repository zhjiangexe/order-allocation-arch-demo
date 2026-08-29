package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Legacy history-visible payload for the {@code pickingAssigned} Temporal signal. */
@Deprecated(forRemoval = false)
public record PickingAssignmentSnapshot(
        UUID pickingId,
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        List<PickingAssignmentSnapshotLine> moves,
        Instant dispatchBy,
        int releasePriority,
        Instant assignedAt) {

    public PickingAssignmentSnapshot {
        Objects.requireNonNull(pickingId, "Picking ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(ownerId, "Owner ID is required");
        Objects.requireNonNull(facilityId, "Facility ID is required");
        Objects.requireNonNull(moves, "Assigned moves are required");
        Objects.requireNonNull(dispatchBy, "Dispatch deadline is required");
        Objects.requireNonNull(assignedAt, "Assignment time is required");
        moves = List.copyOf(moves);
        if (moves.isEmpty() || moves.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Picking assignment requires non-empty moves");
        }
        Set<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Picking assignment requires unique move IDs");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
    }
}
