package com.flowzati.archone.messaging.inbox.infrastructure.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite identity: each stable subscriber may process the same event once. */
@Embeddable
public class InboxId implements Serializable {

  @Column(name = "subscriber_id", nullable = false)
  private String subscriberId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  protected InboxId() {
  }

  public InboxId(String subscriberId, UUID eventId) {
    if (subscriberId == null || subscriberId.isBlank() || eventId == null) {
      throw new IllegalArgumentException("Inbox subscriber and event IDs are required");
    }
    this.subscriberId = subscriberId;
    this.eventId = eventId;
  }

  public String getSubscriberId() {
    return subscriberId;
  }

  public UUID getEventId() {
    return eventId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InboxId inboxId)) {
      return false;
    }
    return subscriberId.equals(inboxId.subscriberId) && eventId.equals(inboxId.eventId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(subscriberId, eventId);
  }
}
