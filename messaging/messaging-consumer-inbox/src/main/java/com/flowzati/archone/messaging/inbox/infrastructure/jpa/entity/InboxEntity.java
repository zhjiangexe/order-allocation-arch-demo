package com.flowzati.archone.messaging.inbox.infrastructure.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_inbox")
public class InboxEntity {

  @EmbeddedId
  private InboxId id;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected InboxEntity() {
  }

  public InboxEntity(
      String subscriberId,
      UUID eventId,
      String eventType,
      Instant processedAt
  ) {
    this.id = new InboxId(subscriberId, eventId);
    this.eventType = eventType;
    this.processedAt = processedAt;
  }

  public UUID getEventId() {
    return id.getEventId();
  }

  public String getSubscriberId() {
    return id.getSubscriberId();
  }

  public String getEventType() {
    return eventType;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
