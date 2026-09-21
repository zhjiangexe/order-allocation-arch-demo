package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import java.util.Objects;

/** Observation payload emitted before an unhandled Integration Event is acknowledged. */
public record UnhandledIntegrationEvent(
        Message message,
        String destination,
        String eventType,
        int contractVersion,
        UnhandledIntegrationEventReason reason) {

    public UnhandledIntegrationEvent {
        Objects.requireNonNull(message, "Unhandled Integration Event message is required");
        if (destination == null
                || destination.isBlank()
                || eventType == null
                || eventType.isBlank()
                || contractVersion < 1) {
            throw new IllegalArgumentException("Unhandled Integration Event fields are required");
        }
        Objects.requireNonNull(reason, "Unhandled Integration Event reason is required");
    }
}
