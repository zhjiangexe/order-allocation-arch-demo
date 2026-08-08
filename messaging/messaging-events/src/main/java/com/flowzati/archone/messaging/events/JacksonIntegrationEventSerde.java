package com.flowzati.archone.messaging.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One Jackson policy shared by outbound serialization and inbound deserialization. */
public final class JacksonIntegrationEventSerde
    implements IntegrationEventSerializer, IntegrationEventDeserializer {

  private final ObjectMapper objectMapper;

  public JacksonIntegrationEventSerde(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String serialize(IntegrationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize integration event", exception);
    }
  }

  @Override
  public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
    try {
      return objectMapper.readValue(payload, eventClass);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Cannot deserialize integration event: " + eventClass.getSimpleName(), exception);
    }
  }
}
