package com.flowzati.archone.ordering.application.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application event stating that Ordering durably accepted a new Order. */
public record OrderPlaced(UUID orderId, UUID ownerId, UUID facilityId, Instant receivedAt) {

    public OrderPlaced {
        Objects.requireNonNull(orderId, "Placed order ID is required");
        Objects.requireNonNull(ownerId, "Placed order owner is required");
        Objects.requireNonNull(facilityId, "Placed order facility is required");
        Objects.requireNonNull(receivedAt, "Placed order receipt time is required");
    }
}
