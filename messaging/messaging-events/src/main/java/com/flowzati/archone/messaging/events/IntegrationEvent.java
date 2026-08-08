package com.flowzati.archone.messaging.events;

import java.util.UUID;

/** Base type for stable events that cross an application or bounded-context boundary. */
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

  /**
   * Stable wire-contract identity used by Outbox and consumers.
   *
   * <p>The value must be explicit. Deriving it from the Java class name would turn an internal
   * refactor into a breaking message-contract change.
   */
  public abstract String eventType();
}
