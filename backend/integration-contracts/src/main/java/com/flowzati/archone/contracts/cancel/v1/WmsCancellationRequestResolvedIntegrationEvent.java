package com.flowzati.archone.contracts.cancel.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** WMS resolved a request without an asynchronous ShipmentCancelled fact. */
public final class WmsCancellationRequestResolvedIntegrationEvent extends IntegrationEvent {

    public static final String EVENT_TYPE = "WmsCancellationRequestResolvedIntegrationEvent";
    public static final int CONTRACT_VERSION = 1;

    public enum Outcome {
        NO_SHIPMENT,
        REJECTED,
        MULTIPLE_SHIPMENTS
    }

    private final UUID requestId;
    private final UUID orderId;
    private final Instant requestedAt;
    private final String reason;
    private final Outcome outcome;

    @JsonCreator
    public WmsCancellationRequestResolvedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("requestId") UUID requestId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("requestedAt") Instant requestedAt,
            @JsonProperty("reason") String reason,
            @JsonProperty("outcome") Outcome outcome) {
        super(eventId);
        this.requestId = Objects.requireNonNull(requestId, "Cancellation request ID is required");
        this.orderId = Objects.requireNonNull(orderId, "Order ID is required");
        this.requestedAt = Objects.requireNonNull(requestedAt, "Request time is required");
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must contain 1 to 512 characters");
        }
        this.reason = reason;
        this.outcome = Objects.requireNonNull(outcome, "WMS outcome is required");
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getReason() {
        return reason;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
