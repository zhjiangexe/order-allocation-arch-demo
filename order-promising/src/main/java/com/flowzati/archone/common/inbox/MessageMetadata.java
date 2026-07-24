package com.flowzati.archone.common.inbox;

import java.util.UUID;

public record MessageMetadata(
    UUID eventId,
    String eventType
) {
  public MessageMetadata {
    if (eventId == null || eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("Message metadata fields are required");
    }
  }
}
