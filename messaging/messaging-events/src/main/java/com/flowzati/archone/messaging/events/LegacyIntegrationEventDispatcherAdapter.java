package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageMetadata;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Temporary adapter for the pre-Tram per-event handler interface.
 *
 * @deprecated application handlers migrate to {@link IntegrationEventHandlersBuilder} in Gate I.
 */
@Deprecated
public final class LegacyIntegrationEventDispatcherAdapter {

  private final IntegrationEventDeserializer deserializer;
  private final Map<IntegrationEventKey, IntegrationEventHandler<?>> handlers;

  public LegacyIntegrationEventDispatcherAdapter(
      IntegrationEventDeserializer deserializer,
      List<IntegrationEventHandler<?>> handlers
  ) {
    if (deserializer == null || handlers == null) {
      throw new IllegalArgumentException("Integration Event dispatcher fields are required");
    }
    this.deserializer = deserializer;
    this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
        handler -> new IntegrationEventKey(handler.destination(), handler.eventType()),
        Function.identity(),
        (first, duplicate) -> {
          throw new IllegalStateException("Duplicate Integration Event handler: "
              + first.destination() + "/" + first.eventType());
        }));
  }

  public boolean supports(String destination, String eventType) {
    return handlers.containsKey(new IntegrationEventKey(destination, eventType));
  }

  public void dispatch(Message message, String expectedDestination, String subscriberId) {
    if (message == null || isBlank(expectedDestination) || isBlank(subscriberId)) {
      throw new IllegalArgumentException("Integration Event dispatch fields are required");
    }
    String eventType = EventMessageHeaders.eventType(message);
    int contractVersion = EventMessageHeaders.contractVersion(message);
    if (contractVersion != EventMessageHeaders.INITIAL_CONTRACT_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported Integration Event contract version: " + eventType + "/" + contractVersion);
    }
    IntegrationEventHandler<?> handler = handlers.get(
        new IntegrationEventKey(expectedDestination, eventType));
    if (handler == null) {
      throw new IllegalArgumentException(
          "Unsupported Integration Event: " + expectedDestination + "/" + eventType);
    }

    MessageMetadata metadata = new MessageMetadata(message.id(), eventType, subscriberId);
    IntegrationEvent event = deserializer.deserialize(message.payload(), handler.eventClass());
    requireMatchingContract(event, metadata);
    handler.handle(event, metadata);
  }

  private void requireMatchingContract(IntegrationEvent event, MessageMetadata metadata) {
    if (!event.getEventId().equals(metadata.eventId())) {
      throw new IllegalArgumentException("Kafka event ID header does not match payload");
    }
    if (!event.eventType().equals(metadata.eventType())) {
      throw new IllegalArgumentException("Kafka event type header does not match payload contract");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record IntegrationEventKey(String destination, String eventType) {
  }
}
