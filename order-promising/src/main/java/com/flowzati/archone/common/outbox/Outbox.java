package com.flowzati.archone.common.outbox;

import java.time.Instant;
import java.util.UUID;

public record Outbox(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String payload,
    Instant occurredAt
) {
  public Outbox {
    if (eventId == null || aggregateType == null || aggregateType.isBlank()
        || aggregateId == null || aggregateId.isBlank() || eventType == null || eventType.isBlank()
        || payload == null || payload.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("Outbox event fields are required");
    }
  }
}
