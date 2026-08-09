package com.flowzati.archone.messaging.events;

import java.util.Objects;
import java.util.function.Consumer;

/** Internal typed registration; application code registers method references through the builder. */
final class IntegrationEventHandlerRegistration<E extends IntegrationEvent> {

  private final String destination;
  private final Class<E> eventClass;
  private final Consumer<IntegrationEventEnvelope<E>> handler;

  IntegrationEventHandlerRegistration(
      String destination,
      Class<E> eventClass,
      Consumer<IntegrationEventEnvelope<E>> handler
  ) {
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("Integration Event destination is required");
    }
    this.destination = destination;
    this.eventClass = Objects.requireNonNull(eventClass, "Integration Event class is required");
    this.handler = Objects.requireNonNull(handler, "Integration Event handler is required");
  }

  String destination() {
    return destination;
  }

  Class<E> eventClass() {
    return eventClass;
  }

  boolean handles(String candidateDestination, Class<? extends IntegrationEvent> candidateClass) {
    return destination.equals(candidateDestination) && eventClass.equals(candidateClass);
  }

  @SuppressWarnings("unchecked")
  void invoke(IntegrationEventEnvelope<? extends IntegrationEvent> envelope) {
    if (!eventClass.isInstance(envelope.event())) {
      throw new IllegalArgumentException(
          "Integration Event envelope does not match registered event class");
    }
    handler.accept((IntegrationEventEnvelope<E>) envelope);
  }
}
