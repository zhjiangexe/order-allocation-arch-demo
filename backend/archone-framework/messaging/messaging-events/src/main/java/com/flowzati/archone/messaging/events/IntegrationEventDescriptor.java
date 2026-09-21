package com.flowzati.archone.messaging.events;

/** Stable external Integration Event identity; it never derives from a Java class name. */
public record IntegrationEventDescriptor(String eventType, int contractVersion) {

    public IntegrationEventDescriptor {
        if (eventType == null || eventType.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Integration Event type fields are required");
        }
    }
}
