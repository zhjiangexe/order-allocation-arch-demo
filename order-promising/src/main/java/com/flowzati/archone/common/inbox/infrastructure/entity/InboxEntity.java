package com.flowzati.archone.common.inbox.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_inbox")
public class InboxEntity {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected InboxEntity() {
  }

  public InboxEntity(UUID eventId, String eventType, Instant processedAt) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.processedAt = processedAt;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
