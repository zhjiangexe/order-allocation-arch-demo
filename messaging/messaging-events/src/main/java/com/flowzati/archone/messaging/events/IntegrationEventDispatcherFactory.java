package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageSubscription;
import java.util.Objects;

/** Creates and immediately subscribes one explicitly owned Integration Event dispatcher. */
public final class IntegrationEventDispatcherFactory {

  private final MessageConsumer messageConsumer;
  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventNameMapping nameMapping;
  private final UnhandledIntegrationEventObserver unhandledEventObserver;

  public IntegrationEventDispatcherFactory(
      MessageConsumer messageConsumer,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping,
      UnhandledIntegrationEventObserver unhandledEventObserver
  ) {
    this.messageConsumer = Objects.requireNonNull(
        messageConsumer, "Message consumer is required");
    this.deserializer = Objects.requireNonNull(
        deserializer, "Integration Event deserializer is required");
    this.nameMapping = Objects.requireNonNull(
        nameMapping, "Integration Event name mapping is required");
    this.unhandledEventObserver = Objects.requireNonNull(
        unhandledEventObserver, "Unhandled Integration Event observer is required");
  }

  /**
   * Tram-compatible basic factory shape.
   *
   * <p>The consumer group starts from the stable subscriber ID and may be transformed by runtime
   * consumer-group mapping. The returned dispatcher is already subscribed.
   */
  public IntegrationEventDispatcher make(String subscriberId, IntegrationEventHandlers handlers) {
    if (subscriberId == null || subscriberId.isBlank() || handlers == null) {
      throw new IllegalArgumentException("Integration Event dispatcher fields are required");
    }
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializer, handlers, nameMapping, unhandledEventObserver);
    MessageSubscription subscription = messageConsumer.subscribe(
        subscriberId,
        handlers.destinations(),
        dispatcher);
    Objects.requireNonNull(subscription, "Message consumer returned no subscription");
    return dispatcher;
  }
}
