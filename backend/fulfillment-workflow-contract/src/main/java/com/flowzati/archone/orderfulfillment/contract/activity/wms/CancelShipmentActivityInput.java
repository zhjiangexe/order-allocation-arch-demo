package com.flowzati.archone.orderfulfillment.contract.activity.wms;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 要求 WMS 冪等取消 Shipment 並回傳安全決策。 */
public record CancelShipmentActivityInput(
        String processId, UUID requestId, UUID orderId, UUID shipmentId, Instant requestedAt, String reason) {

    public CancelShipmentActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(requestId, "Cancellation request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(requestedAt, "Cancellation request time is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
    }
}
