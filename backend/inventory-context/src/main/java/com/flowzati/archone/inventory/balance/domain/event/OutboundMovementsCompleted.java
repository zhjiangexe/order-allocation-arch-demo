package com.flowzati.archone.inventory.balance.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Inventory 已完成一個 allocation 的實體出庫與庫存扣帳。 */
public record OutboundMovementsCompleted(
        UUID allocationId, UUID orderId, UUID shipmentId, List<UUID> movementIds, Instant occurredAt) {

    public OutboundMovementsCompleted {
        Objects.requireNonNull(allocationId, "Allocation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(movementIds, "Movement IDs are required");
        Objects.requireNonNull(occurredAt, "Occurred time is required");
        movementIds = List.copyOf(movementIds);
        if (movementIds.isEmpty()) {
            throw new IllegalArgumentException("Completed movements cannot be empty");
        }
    }
}
