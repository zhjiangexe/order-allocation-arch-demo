package com.flowzati.archone.messaging.outbox.infrastructure.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event_outbox")
public class OutboxEntity {

  @Id
  @Column(name = "id")
  private UUID eventId;

  @Column(name = "aggregatetype", nullable = false)
  private String aggregateType;

  @Column(name = "aggregateid", nullable = false)
  private String aggregateId;

  @Column(name = "type", nullable = false)
  private String eventType;

  @Column(nullable = false)
  private String route;

  @Column(name = "partition_key", nullable = false)
  private String partitionKey;

  @Column(nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @Column(name = "timestamp", nullable = false)
  private Instant occurredAt;

  protected OutboxEntity() {
  }

  public OutboxEntity(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      String eventType,
      String route,
      String partitionKey,
      String payload,
      Instant occurredAt
  ) {
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.route = route;
    this.partitionKey = partitionKey;
    this.payload = payload;
    this.occurredAt = occurredAt;
  }

  public UUID getEventId() { return eventId; }
  public String getAggregateType() { return aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public String getEventType() { return eventType; }
  public String getRoute() { return route; }
  public String getPartitionKey() { return partitionKey; }
  public String getPayload() { return payload; }
  public Instant getOccurredAt() { return occurredAt; }
}
