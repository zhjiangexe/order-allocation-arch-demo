package com.flowzati.archone.contracts.cancel.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

public final class OrderingCancellationRequestedIntegrationEvent extends IntegrationEvent {
    public static final String EVENT_TYPE = "OrderingCancellationRequestedIntegrationEvent";
    public static final int CONTRACT_VERSION = 1;
    private final UUID requestId;
    private final UUID orderId;
    private final Instant requestedAt;
    private final String reason;

    @JsonCreator
    public OrderingCancellationRequestedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("requestId") UUID requestId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("requestedAt") Instant requestedAt,
            @JsonProperty("reason") String reason) {
        super(eventId);
        this.requestId = java.util.Objects.requireNonNull(requestId);
        this.orderId = java.util.Objects.requireNonNull(orderId);
        this.requestedAt = java.util.Objects.requireNonNull(requestedAt);
        this.reason = java.util.Objects.requireNonNull(reason);
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

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
