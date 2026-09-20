package com.flowzati.archone.contracts.cancel.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.util.UUID;

public final class OrderingCancellationRequestResolvedIntegrationEvent extends IntegrationEvent {
    public static final String EVENT_TYPE = "OrderingCancellationRequestResolvedIntegrationEvent";
    public static final int CONTRACT_VERSION = 1;

    public enum Outcome {
        CANCELLED,
        ALREADY_CANCELLED,
        REJECTED
    }

    private final UUID requestId;
    private final UUID orderId;
    private final Outcome outcome;

    @JsonCreator
    public OrderingCancellationRequestResolvedIntegrationEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("requestId") UUID requestId,
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("outcome") Outcome outcome) {
        super(eventId);
        this.requestId = java.util.Objects.requireNonNull(requestId);
        this.orderId = java.util.Objects.requireNonNull(orderId);
        this.outcome = java.util.Objects.requireNonNull(outcome);
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
