package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Workflow 專用契約；adapter 負責從 messaging contract 映射，不直接共用 Kafka event class。 */
public record AllocationSnapshot(
        UUID allocationId,
        UUID orderId,
        UUID ownerId,
        UUID facilityId,
        List<AllocationSnapshotLine> lines,
        Instant dispatchBy,
        int releasePriority,
        Instant committedAt) {

    public AllocationSnapshot {
        Objects.requireNonNull(allocationId, "Allocation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(ownerId, "Owner ID is required");
        Objects.requireNonNull(facilityId, "Facility ID is required");
        Objects.requireNonNull(lines, "Allocation lines are required");
        Objects.requireNonNull(dispatchBy, "Dispatch deadline is required");
        Objects.requireNonNull(committedAt, "Allocation commit time is required");
        lines = List.copyOf(lines);
        if (lines.isEmpty() || lines.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Allocation requires non-empty lines");
        }
        Set<UUID> moveIds = new HashSet<>();
        if (lines.stream().anyMatch(line -> !moveIds.add(line.moveId()))) {
            throw new IllegalArgumentException("Allocation requires unique move IDs");
        }
        if (releasePriority < 0 || releasePriority > 100) {
            throw new IllegalArgumentException("Release priority must be between 0 and 100");
        }
    }
}
