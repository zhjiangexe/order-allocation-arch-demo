package com.flowzati.archone.messaging.api;

import java.util.UUID;

/**
 * Metadata required to process one inbound message idempotently.
 *
 * <p>{@code subscriberId} is the stable local subscription or idempotency-scope identity. The
 * receiving adapter supplies it; it is not trusted from an external broker header.
 */
public record MessageMetadata(UUID eventId, String eventType, String subscriberId) {
    public MessageMetadata {
        if (eventId == null || isBlank(eventType) || isBlank(subscriberId)) {
            throw new IllegalArgumentException("Message metadata fields are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
