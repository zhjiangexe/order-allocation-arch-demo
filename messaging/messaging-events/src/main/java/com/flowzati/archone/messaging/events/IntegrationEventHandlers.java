package com.flowzati.archone.messaging.events;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable handler group explicitly owned by one application dispatcher. */
public final class IntegrationEventHandlers {

  private final List<IntegrationEventHandlerRegistration<?>> handlers;
  private final Set<String> destinations;

  IntegrationEventHandlers(List<IntegrationEventHandlerRegistration<?>> handlers) {
    if (handlers == null || handlers.isEmpty()) {
      throw new IllegalArgumentException("At least one Integration Event handler is required");
    }
    this.handlers = List.copyOf(handlers);
    LinkedHashSet<String> registeredDestinations = new LinkedHashSet<>();
    this.handlers.forEach(handler -> registeredDestinations.add(handler.destination()));
    this.destinations = Collections.unmodifiableSet(registeredDestinations);
  }

  public Set<String> destinations() {
    return destinations;
  }

  Optional<IntegrationEventHandlerRegistration<?>> find(
      String destination,
      Class<? extends IntegrationEvent> eventClass
  ) {
    return handlers.stream()
        .filter(handler -> handler.handles(destination, eventClass))
        .findFirst();
  }

  Set<Class<? extends IntegrationEvent>> eventClasses() {
    LinkedHashSet<Class<? extends IntegrationEvent>> classes = new LinkedHashSet<>();
    handlers.forEach(handler -> classes.add(handler.eventClass()));
    return Collections.unmodifiableSet(classes);
  }
}
