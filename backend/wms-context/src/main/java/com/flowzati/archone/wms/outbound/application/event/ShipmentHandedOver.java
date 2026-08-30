package com.flowzati.archone.wms.outbound.application.event;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application event stating that WMS transferred Shipment custody to the carrier. */
public record ShipmentHandedOver(
        UUID shipmentId, UUID stockOperationId, UUID orderId, List<UUID> movementIds, Instant handedOverAt) {

    public ShipmentHandedOver {
        Objects.requireNonNull(shipmentId, "Handed-over shipment ID is required");
        Objects.requireNonNull(stockOperationId, "Handed-over stock operation ID is required");
        Objects.requireNonNull(orderId, "Handed-over order ID is required");
        Objects.requireNonNull(handedOverAt, "Shipment handover time is required");
        if (movementIds == null || movementIds.isEmpty()) {
            throw new IllegalArgumentException("Handed-over shipment requires movement IDs");
        }
        movementIds = List.copyOf(movementIds);
        if (movementIds.stream().anyMatch(Objects::isNull) || new HashSet<>(movementIds).size() != movementIds.size()) {
            throw new IllegalArgumentException("Handed-over shipment requires unique non-null movement IDs");
        }
    }

    public static ShipmentHandedOver from(Shipment shipment, Instant handedOverAt) {
        Objects.requireNonNull(shipment, "Handed-over shipment is required");
        return new ShipmentHandedOver(
                shipment.id(),
                shipment.stockOperationId(),
                shipment.orderId(),
                shipment.lines().stream().map(ShipmentLine::moveId).toList(),
                handedOverAt);
    }
}
