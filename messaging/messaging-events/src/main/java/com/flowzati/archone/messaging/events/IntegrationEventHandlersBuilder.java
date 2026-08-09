package com.flowzati.archone.messaging.events;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Tram-shaped DSL for registering typed Integration Event method references. */
public final class IntegrationEventHandlersBuilder {

  private final List<IntegrationEventHandlerRegistration<?>> handlers = new ArrayList<>();
  private final Set<HandlerKey> registeredKeys = new LinkedHashSet<>();
  private String destination;

  private IntegrationEventHandlersBuilder(String destination) {
    this.destination = requireDestination(destination);
  }

  public static IntegrationEventHandlersBuilder forDestination(String destination) {
    return new IntegrationEventHandlersBuilder(destination);
  }

  public <E extends IntegrationEvent> IntegrationEventHandlersBuilder onEvent(
      Class<E> eventClass,
      Consumer<IntegrationEventEnvelope<E>> handler
  ) {
    Objects.requireNonNull(eventClass, "Integration Event class is required");
    Objects.requireNonNull(handler, "Integration Event handler is required");
    HandlerKey key = new HandlerKey(destination, eventClass);
    if (!registeredKeys.add(key)) {
      throw new IllegalStateException("Duplicate Integration Event handler: "
          + destination + "/" + eventClass.getName());
    }
    handlers.add(new IntegrationEventHandlerRegistration<>(destination, eventClass, handler));
    return this;
  }

  public IntegrationEventHandlersBuilder andForDestination(String destination) {
    this.destination = requireDestination(destination);
    return this;
  }

  public IntegrationEventHandlers build() {
    return new IntegrationEventHandlers(handlers);
  }

  private static String requireDestination(String destination) {
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("Integration Event destination is required");
    }
    return destination;
  }

  private record HandlerKey(
      String destination,
      Class<? extends IntegrationEvent> eventClass
  ) {
  }
}
