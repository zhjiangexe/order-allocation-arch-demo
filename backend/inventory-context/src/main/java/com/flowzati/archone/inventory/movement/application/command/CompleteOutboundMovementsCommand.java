package com.flowzati.archone.inventory.movement.application.command;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Normalized proof that one Shipment handover completed every movement in an outbound operation. */
public record CompleteOutboundMovementsCommand(
        UUID orderId, UUID shipmentId, UUID expectedStockOperationId, List<UUID> movementIds, Instant completedAt) {

    public CompleteOutboundMovementsCommand {
        Objects.requireNonNull(orderId, "Outbound completion order ID is required");
        Objects.requireNonNull(shipmentId, "Outbound completion shipment ID is required");
        Objects.requireNonNull(movementIds, "Outbound completion movement IDs are required");
        Objects.requireNonNull(completedAt, "Outbound completion time is required");
        movementIds = List.copyOf(movementIds);
        if (movementIds.isEmpty() || movementIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Outbound completion requires at least one movement ID");
        }
        if (new HashSet<>(movementIds).size() != movementIds.size()) {
            throw new IllegalArgumentException("Outbound completion movement IDs must be unique");
        }
    }
}
