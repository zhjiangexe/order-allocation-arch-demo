package com.flowzati.archone.inventory.balance.application.command;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** WMS 完成交接後，要求 Inventory 將一個 allocation 的實體出庫正式過帳。 */
public record CompleteOutboundMovementsCommand(
        UUID allocationId, UUID orderId, UUID shipmentId, List<UUID> movementIds, Instant completedAt) {

    public CompleteOutboundMovementsCommand {
        Objects.requireNonNull(allocationId, "Allocation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(movementIds, "Movement IDs are required");
        Objects.requireNonNull(completedAt, "Outbound completion time is required");
        movementIds = List.copyOf(movementIds);
        if (movementIds.isEmpty() || movementIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("At least one movement ID is required");
        }
        if (new HashSet<>(movementIds).size() != movementIds.size()) {
            throw new IllegalArgumentException("Movement IDs must be unique");
        }
    }
}
