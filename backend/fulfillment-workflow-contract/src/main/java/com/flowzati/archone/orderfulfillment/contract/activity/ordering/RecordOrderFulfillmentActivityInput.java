package com.flowzati.archone.orderfulfillment.contract.activity.ordering;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 要求 Ordering 將整張訂單冪等推進到 FULFILLED。 */
public record RecordOrderFulfillmentActivityInput(
        String processId, UUID orderId, UUID shipmentId, Instant fulfilledAt) {

    public RecordOrderFulfillmentActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(fulfilledAt, "Fulfilled time is required");
    }
}
