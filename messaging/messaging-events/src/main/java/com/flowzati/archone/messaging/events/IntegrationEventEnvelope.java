package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import java.util.UUID;

/**
 * Immutable typed envelope delivered to an Integration Event handler.
 *
 * <p>It deliberately contains no subscriber, Kafka, retry, or Inbox metadata. Transport-neutral
 * message headers remain available through {@link #message()}.
 */
public record IntegrationEventEnvelope<E extends IntegrationEvent>(
    Message message,
    String aggregateType,
    String aggregateId,
    UUID eventId,
    E event
) {

  public IntegrationEventEnvelope {
    if (message == null || isBlank(aggregateType) || isBlank(aggregateId)
        || eventId == null || event == null) {
      throw new IllegalArgumentException("Integration Event envelope fields are required");
    }
    if (!message.id().equals(eventId)) {
      throw new IllegalArgumentException("Integration Event ID does not match message ID");
    }
    if (!event.getEventId().equals(eventId)) {
      throw new IllegalArgumentException("Integration Event ID does not match payload");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
