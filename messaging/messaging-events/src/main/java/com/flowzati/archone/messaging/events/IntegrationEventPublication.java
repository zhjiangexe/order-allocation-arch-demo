package com.flowzati.archone.messaging.events;

import java.time.Instant;

/** Complete publication request before it is converted into a generic message. */
public record IntegrationEventPublication(
    IntegrationEvent event,
    AggregateReference aggregate,
    PublicationTarget target,
    Instant occurredAt
) {
  public IntegrationEventPublication {
    if (event == null || aggregate == null || target == null || occurredAt == null) {
      throw new IllegalArgumentException("Integration Event publication fields are required");
    }
  }
}
