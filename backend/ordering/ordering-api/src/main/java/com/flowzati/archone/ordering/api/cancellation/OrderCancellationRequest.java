package com.flowzati.archone.ordering.api.cancellation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrderCancellationRequest(UUID requestId, UUID orderId, Instant requestedAt, String reason) {

    public OrderCancellationRequest {
        Objects.requireNonNull(requestId, "Cancellation request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(requestedAt, "Cancellation request time is required");
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must contain 1 to 512 characters");
        }
    }
}
