package com.flowzati.archone.messaging.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport-neutral message passed to a producer implementation.
 *
 * <p>The destination is deliberately not part of this value. As in Eventuate Tram,
 * {@link MessageProducer#send(String, Message)} receives the logical destination separately, while
 * this object carries message identity, aggregate identity, ordering key and payload.
 */
public record Message(
    UUID id,
    String type,
    String aggregateType,
    String aggregateId,
    String partitionKey,
    String payload,
    Instant occurredAt
) {
  public Message {
    if (id == null || isBlank(type) || isBlank(aggregateType) || isBlank(aggregateId)
        || isBlank(partitionKey) || isBlank(payload) || occurredAt == null) {
      throw new IllegalArgumentException("Message fields are required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
