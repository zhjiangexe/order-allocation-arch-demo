package com.flowzati.archone.ordering.application.command;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 將已完成的出庫履約事實寫回 Ordering。Shipment ID 是重播時核對來源事實的 correlation key。 */
public record RecordOrderFulfillmentCommand(UUID orderId, UUID shipmentId, Instant fulfilledAt) {

    public RecordOrderFulfillmentCommand {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(fulfilledAt, "Fulfilled time is required");
    }
}
