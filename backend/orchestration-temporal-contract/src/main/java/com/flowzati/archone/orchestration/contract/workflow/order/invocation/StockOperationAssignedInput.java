package com.flowzati.archone.orchestration.contract.workflow.order.invocation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Workflow invocation data copied from an Inventory stock-operation assignment fact; also used to create a Shipment. */
public record StockOperationAssignedInput(
        UUID stockOperationId,
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        List<AssignedStockMove> moves,
        Instant dispatchBy,
        int releasePriority,
        Instant assignedAt) {

    public StockOperationAssignedInput {
        Objects.requireNonNull(stockOperationId, "Stock operation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(ownerId, "Owner ID is required");
        Objects.requireNonNull(facilityId, "Facility ID is required");
        Objects.requireNonNull(moves, "Assigned moves are required");
        Objects.requireNonNull(dispatchBy, "Dispatch deadline is required");
        Objects.requireNonNull(assignedAt, "Assignment time is required");
        moves = List.copyOf(moves);
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("Stock operation assignment requires non-empty moves");
        }
        Set<UUID> moveIds = new HashSet<>();
        if (moves.stream().anyMatch(move -> !moveIds.add(move.moveId()))) {
            throw new IllegalArgumentException("Stock operation assignment requires unique move IDs");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
    }
}
