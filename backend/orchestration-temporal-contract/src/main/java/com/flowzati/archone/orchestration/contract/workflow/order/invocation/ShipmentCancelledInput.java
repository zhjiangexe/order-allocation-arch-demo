package com.flowzati.archone.orchestration.contract.workflow.order.invocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Workflow 專用訊息，由 adapter 從 WMS Shipment cancellation integration event 映射而來。 */
public record ShipmentCancelledInput(UUID orderId, UUID shipmentId, UUID cancellationRequestId, Instant cancelledAt) {

    public ShipmentCancelledInput {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(cancellationRequestId, "Cancellation request ID is required");
        Objects.requireNonNull(cancelledAt, "Cancellation completion time is required");
    }
}
