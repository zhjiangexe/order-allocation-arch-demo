package com.flowzati.archone.orderfulfillment.contract.activity.inventory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 承運商交接後，要求 Inventory 完成 outbound movements 與實際庫存扣帳。 */
public record CompleteOutboundMovementsActivityInput(
        String processId,
        UUID orderId,
        UUID stockOperationId,
        UUID shipmentId,
        List<UUID> movementIds,
        Instant handedOverAt) {

    public CompleteOutboundMovementsActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(stockOperationId, "Stock operation ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(movementIds, "Movement IDs are required");
        Objects.requireNonNull(handedOverAt, "Handover time is required");
        movementIds = List.copyOf(movementIds);
        if (movementIds.isEmpty() || movementIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("At least one movement ID is required");
        }
        Set<UUID> uniqueMovementIds = new HashSet<>(movementIds);
        if (uniqueMovementIds.size() != movementIds.size()) {
            throw new IllegalArgumentException("Movement IDs must be unique");
        }
    }
}
