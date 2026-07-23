package com.flowzati.archone.common.integration;

import java.util.UUID;

public abstract class IntegrationEvent {
  private final UUID eventId;

  protected IntegrationEvent(UUID eventId) {
    if (eventId == null) {
      throw new IllegalArgumentException("Event ID is required");
    }
    this.eventId = eventId;
  }

  public UUID getEventId() {
    return eventId;
  }
}
