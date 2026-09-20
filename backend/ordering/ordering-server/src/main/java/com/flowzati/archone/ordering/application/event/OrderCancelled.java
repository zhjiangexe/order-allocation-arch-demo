package com.flowzati.archone.ordering.application.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application event stating that an Order reached its cancelled state. */
public record OrderCancelled(UUID orderId, UUID ownerId, UUID facilityId, Instant cancelledAt) {

    public OrderCancelled {
        Objects.requireNonNull(orderId, "Cancelled order ID is required");
        Objects.requireNonNull(ownerId, "Cancelled order owner is required");
        Objects.requireNonNull(facilityId, "Cancelled order facility is required");
        Objects.requireNonNull(cancelledAt, "Order cancellation time is required");
    }
}
