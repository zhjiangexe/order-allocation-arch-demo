package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import java.util.Objects;

/** Broker-neutral typed Integration Event dispatcher. */
public final class IntegrationEventDispatcher implements MessageHandler {

  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventHandlers handlers;
  private final IntegrationEventNameMapping nameMapping;
  private final IntegrationEventDispatcherOptions options;

  public IntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      IntegrationEventHandlers handlers,
      IntegrationEventNameMapping nameMapping
  ) {
    this(deserializer, handlers, nameMapping, IntegrationEventDispatcherOptions.strict());
  }

  public IntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      IntegrationEventHandlers handlers,
      IntegrationEventNameMapping nameMapping,
      IntegrationEventDispatcherOptions options
  ) {
    this.deserializer = Objects.requireNonNull(
        deserializer, "Integration Event deserializer is required");
    this.handlers = Objects.requireNonNull(handlers, "Integration Event handlers are required");
    this.nameMapping = Objects.requireNonNull(
        nameMapping, "Integration Event name mapping is required");
    this.options = Objects.requireNonNull(options, "Integration Event dispatcher options are required");
    validateNameMappings();
  }

  public boolean supports(String destination, String eventType, int contractVersion) {
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("Integration Event destination is required");
    }
    return nameMapping.eventClassFor(eventType, contractVersion)
        .flatMap(eventClass -> handlers.find(destination, eventClass))
        .isPresent();
  }

  @Override
  public void handle(Message message, MessageContext context) {
    Objects.requireNonNull(context, "Message context is required");
    dispatch(message, context.logicalChannel());
  }

  public void dispatch(Message message, String expectedDestination) {
    if (message == null || isBlank(expectedDestination)) {
      throw new IllegalArgumentException("Integration Event dispatch fields are required");
    }
    String eventType = EventMessageHeaders.eventType(message);
    int contractVersion = EventMessageHeaders.contractVersion(message);
    IntegrationEventType externalType = new IntegrationEventType(eventType, contractVersion);
    Class<? extends IntegrationEvent> eventClass = nameMapping.eventClassFor(externalType)
        .orElse(null);
    if (eventClass == null) {
      handleUnhandled(
          message,
          expectedDestination,
          externalType,
          UnhandledIntegrationEventReason.UNKNOWN_TYPE_VERSION);
      return;
    }
    IntegrationEventHandlerRegistration<?> handler = handlers.find(
        expectedDestination, eventClass).orElse(null);
    if (handler == null) {
      handleUnhandled(
          message,
          expectedDestination,
          externalType,
          UnhandledIntegrationEventReason.NO_HANDLER_FOR_DESTINATION);
      return;
    }
    if (!externalType.equals(nameMapping.externalTypeFor(eventClass))) {
      throw new IllegalStateException("Integration Event name mapping is not bidirectional: "
          + eventType + "/" + contractVersion);
    }

    String aggregateType = message.requiredHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE);
    String aggregateId = message.requiredHeader(EventMessageHeaders.EVENT_AGGREGATE_ID);
    IntegrationEvent event = deserializer.deserialize(message.payload(), eventClass);
    requireMatchingContract(message, externalType, event);
    handler.invoke(new IntegrationEventEnvelope<>(
        message,
        aggregateType,
        aggregateId,
        message.id(),
        event));
  }

  private void handleUnhandled(
      Message message,
      String destination,
      IntegrationEventType externalType,
      UnhandledIntegrationEventReason reason
  ) {
    if (options.unhandledEventPolicy() == UnhandledEventPolicy.FAIL) {
      if (reason == UnhandledIntegrationEventReason.UNKNOWN_TYPE_VERSION) {
        throw new IllegalArgumentException("Unsupported Integration Event type/version: "
            + externalType.eventType() + "/" + externalType.contractVersion());
      }
      throw new IllegalArgumentException("Unsupported Integration Event handler: "
          + destination + "/" + externalType.eventType() + "/"
          + externalType.contractVersion());
    }
    UnhandledIntegrationEventObserver observer = options.unhandledEventObserver()
        .orElseThrow(() -> new IllegalStateException(
            "IGNORE_WITH_METRIC requires an unhandled Integration Event observer"));
    observer.onUnhandled(new UnhandledIntegrationEvent(
        message,
        destination,
        externalType.eventType(),
        externalType.contractVersion(),
        reason));
  }

  private void requireMatchingContract(
      Message message,
      IntegrationEventType externalType,
      IntegrationEvent event
  ) {
    if (!event.getEventId().equals(message.id())) {
      throw new IllegalArgumentException("Integration Event ID header does not match payload");
    }
    if (!event.eventType().equals(externalType.eventType())) {
      throw new IllegalArgumentException(
          "Integration Event type header does not match payload contract");
    }
  }

  private void validateNameMappings() {
    handlers.eventClasses().forEach(eventClass -> {
      IntegrationEventType externalType = Objects.requireNonNull(
          nameMapping.externalTypeFor(eventClass),
          "Integration Event name mapping returned no external type");
      Class<? extends IntegrationEvent> reverseMapped = Objects.requireNonNull(
              nameMapping.eventClassFor(externalType),
              "Integration Event name mapping returned no reverse result")
          .orElseThrow(() -> new IllegalStateException(
              "Integration Event name mapping is not bidirectional: "
                  + externalType.eventType() + "/" + externalType.contractVersion()));
      if (!eventClass.equals(reverseMapped)) {
        throw new IllegalStateException("Integration Event name mapping is not bidirectional: "
            + externalType.eventType() + "/" + externalType.contractVersion());
      }
    });
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
