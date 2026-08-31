package com.flowzati.archone.wms.shipment.application.event;

import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application event stating that WMS completed a Shipment cancellation. */
public record ShipmentCancelled(
        UUID shipmentId,
        UUID stockOperationId,
        UUID orderId,
        UUID cancellationRequestId,
        Instant cancellationRequestedAt,
        String cancellationReason,
        Instant cancelledAt) {

    public ShipmentCancelled {
        Objects.requireNonNull(shipmentId, "Cancelled shipment ID is required");
        Objects.requireNonNull(stockOperationId, "Cancelled shipment stock operation ID is required");
        Objects.requireNonNull(orderId, "Cancelled shipment order ID is required");
        Objects.requireNonNull(cancellationRequestId, "Shipment cancellation request ID is required");
        Objects.requireNonNull(cancellationRequestedAt, "Shipment cancellation request time is required");
        Objects.requireNonNull(cancelledAt, "Shipment cancellation completion time is required");
        if (cancellationReason == null || cancellationReason.isBlank()) {
            throw new IllegalArgumentException("Shipment cancellation reason is required");
        }
    }

    public static ShipmentCancelled from(Shipment shipment) {
        Objects.requireNonNull(shipment, "Cancelled shipment is required");
        return new ShipmentCancelled(
                shipment.id(),
                shipment.stockOperationId(),
                shipment.orderId(),
                shipment.cancellationRequestId(),
                shipment.cancellationRequestedAt(),
                shipment.cancellationReason(),
                shipment.cancelledAt());
    }
}
