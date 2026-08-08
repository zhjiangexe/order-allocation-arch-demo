package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Kafka boundary: validates metadata, deserializes payloads, and dispatches typed events. */
public final class KafkaIntegrationEventDispatcher {

  private final IntegrationEventDeserializer deserializer;
  private final Map<KafkaIntegrationEventKey, IntegrationEventHandler<?>> handlers;

  public KafkaIntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers
  ) {
    this.deserializer = deserializer;
    this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
        handler -> new KafkaIntegrationEventKey(handler.destination(), handler.eventType()),
        Function.identity(),
        (first, duplicate) -> {
          throw new IllegalStateException("Duplicate Integration Event handler: "
              + first.destination() + "/" + first.eventType());
        }));
  }

  public void dispatch(
      ConsumerRecord<String, String> record,
      String expectedDestination,
      String subscriberId
  ) {
    MessageMetadata metadata = metadata(record, subscriberId);
    KafkaIntegrationEventKey key =
        new KafkaIntegrationEventKey(expectedDestination, metadata.eventType());
    IntegrationEventHandler<?> handler = handlers.get(key);
    if (handler == null) {
      throw new IllegalArgumentException("Unsupported Kafka integration event: "
          + expectedDestination + "/" + metadata.eventType());
    }

    IntegrationEvent event = deserializer.deserialize(record.value(), handler.eventClass());
    requireMatchingContract(event, metadata);
    handler.handle(event, metadata);
  }

  private MessageMetadata metadata(ConsumerRecord<String, String> record, String subscriberId) {
    return new MessageMetadata(
        UUID.fromString(requiredHeader(record, "id")),
        requiredHeader(record, "eventType"),
        subscriberId);
  }

  private String requiredHeader(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null || header.value().length == 0) {
      throw new IllegalArgumentException("Missing Kafka header: " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  private void requireMatchingContract(IntegrationEvent event, MessageMetadata metadata) {
    if (!event.getEventId().equals(metadata.eventId())) {
      throw new IllegalArgumentException("Kafka event ID header does not match payload");
    }
    if (!event.eventType().equals(metadata.eventType())) {
      throw new IllegalArgumentException("Kafka event type header does not match payload contract");
    }
  }
}
