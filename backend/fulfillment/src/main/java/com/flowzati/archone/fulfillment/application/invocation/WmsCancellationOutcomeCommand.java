package com.flowzati.archone.fulfillment.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** Transport-independent WMS outcome correlated with the immutable cancellation request. */
public record WmsCancellationOutcomeCommand(
        UUID requestId, UUID orderId, Instant requestedAt, String reason, Outcome outcome) {
    public enum Outcome {
        SHIPMENT_CANCELLED,
        NO_SHIPMENT,
        REJECTED,
        MULTIPLE_SHIPMENTS
    }
}
