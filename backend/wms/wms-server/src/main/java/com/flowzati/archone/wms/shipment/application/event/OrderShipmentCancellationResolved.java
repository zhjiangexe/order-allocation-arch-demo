package com.flowzati.archone.wms.shipment.application.event;

import java.time.Instant;
import java.util.UUID;

/** WMS found no Shipment to cancel, or could not accept cancellation. */
public record OrderShipmentCancellationResolved(
        UUID requestId, UUID orderId, Instant requestedAt, String reason, Outcome outcome) {
    public enum Outcome {
        NO_SHIPMENT,
        REJECTED,
        MULTIPLE_SHIPMENTS
    }
}
