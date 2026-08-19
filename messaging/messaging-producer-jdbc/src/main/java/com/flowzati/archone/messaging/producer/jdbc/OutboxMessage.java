package com.flowzati.archone.messaging.producer.jdbc;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Framework-neutral representation of one row appended to the transactional Outbox. */
public record OutboxMessage(
        UUID messageId,
        Optional<String> aggregateType,
        Optional<String> aggregateId,
        String messageType,
        String destination,
        String partitionKey,
        String payload,
        Instant createdAt,
        Map<String, String> serializedHeaders) {

    public OutboxMessage {
        if (messageId == null
                || aggregateType == null
                || aggregateId == null
                || createdAt == null
                || serializedHeaders == null
                || isBlank(messageType)
                || isBlank(destination)
                || isBlank(partitionKey)
                || isBlank(payload)) {
            throw new IllegalArgumentException("Outbox message fields are required");
        }
        if (aggregateType.isPresent() != aggregateId.isPresent()) {
            throw new IllegalArgumentException(
                    "Outbox aggregate type and ID must either both be present or both be absent");
        }
        aggregateType.ifPresent(value -> requireText("Outbox aggregate type", value));
        aggregateId.ifPresent(value -> requireText("Outbox aggregate ID", value));
        serializedHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(serializedHeaders));
    }

    private static void requireText(String field, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
