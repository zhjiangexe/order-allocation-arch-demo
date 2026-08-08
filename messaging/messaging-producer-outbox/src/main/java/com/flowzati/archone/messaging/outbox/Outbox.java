package com.flowzati.archone.messaging.outbox;

import java.time.Instant;
import java.util.UUID;

/** Persistent Outbox row before it is mapped to the JPA entity. */
public record Outbox(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String route,
    String partitionKey,
    String payload,
    Instant occurredAt
) {
  public Outbox {
    if (eventId == null || isBlank(aggregateType) || isBlank(aggregateId) || isBlank(eventType)
        || isBlank(route) || isBlank(partitionKey) || isBlank(payload) || occurredAt == null) {
      throw new IllegalArgumentException("Outbox event fields are required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
