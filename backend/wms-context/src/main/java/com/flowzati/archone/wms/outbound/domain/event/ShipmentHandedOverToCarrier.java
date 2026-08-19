package com.flowzati.archone.wms.outbound.domain.event;

import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** WMS 已把貨物 custody 交給承運人；不代表 TMS 的車輛已離站。 */
public record ShipmentHandedOverToCarrier(
        UUID shipmentId, UUID allocationId, UUID orderId, List<UUID> movementIds, Instant occurredAt)
        implements WmsDomainEvent {

    public ShipmentHandedOverToCarrier {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(allocationId, "Allocation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(movementIds, "Movement IDs are required");
        Objects.requireNonNull(occurredAt, "Occurred time is required");
        movementIds = List.copyOf(movementIds);
        if (movementIds.isEmpty()) {
            throw new IllegalArgumentException("Shipment handover requires movement IDs");
        }
    }
}
