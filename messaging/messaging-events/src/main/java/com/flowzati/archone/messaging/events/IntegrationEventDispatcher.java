package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandlingOutcome;
import com.flowzati.archone.messaging.api.OutcomeAwareMessageHandler;
import java.util.Objects;

/** Broker-neutral typed Integration Event dispatcher. */
public final class IntegrationEventDispatcher implements OutcomeAwareMessageHandler {

  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventHandlers handlers;
  private final IntegrationEventNameMapping nameMapping;
  private final UnhandledIntegrationEventObserver unhandledEventObserver;

  public IntegrationEventDispatcher(
      IntegrationEventDeserializer deserializer,
      IntegrationEventHandlers handlers,
      IntegrationEventNameMapping nameMapping,
      UnhandledIntegrationEventObserver unhandledEventObserver
  ) {
    this.deserializer = Objects.requireNonNull(
        deserializer, "Integration Event deserializer is required");
    this.handlers = Objects.requireNonNull(handlers, "Integration Event handlers are required");
    this.nameMapping = Objects.requireNonNull(
        nameMapping, "Integration Event name mapping is required");
    this.unhandledEventObserver = Objects.requireNonNull(
        unhandledEventObserver, "Unhandled Integration Event observer is required");
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
  public MessageHandlingOutcome handleWithOutcome(Message message, MessageContext context) {
    Objects.requireNonNull(context, "Message context is required");
    return dispatchWithOutcome(message, context.logicalChannel());
  }

  /** Compatibility entrypoint for callers that do not consume semantic outcomes. */
  public void dispatch(Message message, String expectedDestination) {
    dispatchWithOutcome(message, expectedDestination);
  }

  /** Returns ignored only after the global observer has accepted an unhandled event. */
  public MessageHandlingOutcome dispatchWithOutcome(
      Message message,
      String expectedDestination
  ) {
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
      return MessageHandlingOutcome.IGNORED_UNHANDLED;
    }
    IntegrationEventHandlerRegistration<?> handler = handlers.find(
        expectedDestination, eventClass).orElse(null);
    if (handler == null) {
      handleUnhandled(
          message,
          expectedDestination,
          externalType,
          UnhandledIntegrationEventReason.NO_HANDLER_FOR_DESTINATION);
      return MessageHandlingOutcome.IGNORED_UNHANDLED;
    }
    if (!externalType.equals(nameMapping.externalTypeFor(eventClass))) {
      throw new IllegalStateException("Integration Event name mapping is not bidirectional: "
          + eventType + "/" + contractVersion);
    }

    String aggregateType = requiredContractHeader(
        message, EventMessageHeaders.EVENT_AGGREGATE_TYPE);
    String aggregateId = requiredContractHeader(
        message, EventMessageHeaders.EVENT_AGGREGATE_ID);
    IntegrationEvent event = deserialize(message, eventClass);
    requireMatchingContract(message, externalType, event);
    handler.invoke(new IntegrationEventEnvelope<>(
        message,
        aggregateType,
        aggregateId,
        message.id(),
        event));
    return MessageHandlingOutcome.PROCESSED;
  }

  private String requiredContractHeader(Message message, String name) {
    try {
      return message.requiredHeader(name);
    } catch (IllegalArgumentException exception) {
      throw new IntegrationEventContractException(exception.getMessage(), exception);
    }
  }

  private IntegrationEvent deserialize(
      Message message,
      Class<? extends IntegrationEvent> eventClass
  ) {
    try {
      return deserializer.deserialize(message.payload(), eventClass);
    } catch (IntegrationEventContractException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new IntegrationEventContractException(exception.getMessage(), exception);
    }
  }

  private void handleUnhandled(
      Message message,
      String destination,
      IntegrationEventType externalType,
      UnhandledIntegrationEventReason reason
  ) {
    unhandledEventObserver.onUnhandled(new UnhandledIntegrationEvent(
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
      throw new IntegrationEventContractException(
          "Integration Event ID header does not match payload");
    }
    if (!event.eventType().equals(externalType.eventType())) {
      throw new IntegrationEventContractException(
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
