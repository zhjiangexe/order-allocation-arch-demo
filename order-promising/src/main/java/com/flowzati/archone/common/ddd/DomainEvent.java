package com.flowzati.archone.common.ddd;

import java.util.UUID;

public abstract class DomainEvent {
  private UUID eventId;

  public UUID getEventId() {
    return eventId;
  }

  public void setEventId(UUID eventId) {
    this.eventId = eventId;
  }
}
