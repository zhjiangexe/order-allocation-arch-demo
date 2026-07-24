package com.flowzati.archone.common.messaging.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/** Shared Kafka boundary: validates metadata, deserializes payloads, and dispatches typed events. */
@Component
public class KafkaIntegrationEventDispatcher {

  private final ObjectMapper objectMapper;
  private final Map<KafkaIntegrationEventKey, IntegrationEventHandler<?>> handlers;

  public KafkaIntegrationEventDispatcher(
      ObjectMapper objectMapper,
      List<IntegrationEventHandler<?>> handlers
  ) {
    this.objectMapper = objectMapper;
    this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
        handler -> new KafkaIntegrationEventKey(handler.topic(), handler.eventType()),
        Function.identity(),
        (first, duplicate) -> {
          throw new IllegalStateException("Duplicate Integration Event handler: "
              + first.topic() + "/" + first.eventType());
        }));
  }

  public void dispatch(ConsumerRecord<String, String> record, String expectedTopic) {
    MessageMetadata metadata = metadata(record);
    KafkaIntegrationEventKey key = new KafkaIntegrationEventKey(expectedTopic, metadata.eventType());
    IntegrationEventHandler<?> handler = handlers.get(key);
    if (handler == null) {
      throw new IllegalArgumentException("Unsupported Kafka integration event: "
          + expectedTopic + "/" + metadata.eventType());
    }

    IntegrationEvent event = deserialize(record.value(), handler.eventClass());
    requireMatchingEventId(event, metadata);
    handler.handle(event, metadata);
  }

  private MessageMetadata metadata(ConsumerRecord<String, String> record) {
    return new MessageMetadata(
        UUID.fromString(requiredHeader(record, "id")),
        requiredHeader(record, "eventType"));
  }

  private String requiredHeader(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null || header.value().length == 0) {
      throw new IllegalArgumentException("Missing Kafka header: " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  private <T extends IntegrationEvent> T deserialize(String payload, Class<T> eventType) {
    try {
      return objectMapper.readValue(payload, eventType);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot deserialize integration event: " + eventType.getSimpleName(),
          exception);
    }
  }

  private void requireMatchingEventId(IntegrationEvent event, MessageMetadata metadata) {
    if (!event.getEventId().equals(metadata.eventId())) {
      throw new IllegalArgumentException("Kafka event ID header does not match payload");
    }
  }
}
