package com.flowzati.archone.messaging.events;

import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import java.util.Objects;

/** Creates and immediately subscribes one explicitly owned Integration Event dispatcher. */
public final class IntegrationEventDispatcherFactory {

  private final MessageConsumer messageConsumer;
  private final IntegrationEventDeserializer deserializer;
  private final IntegrationEventNameMapping nameMapping;

  public IntegrationEventDispatcherFactory(
      MessageConsumer messageConsumer,
      IntegrationEventDeserializer deserializer,
      IntegrationEventNameMapping nameMapping
  ) {
    this.messageConsumer = Objects.requireNonNull(
        messageConsumer, "Message consumer is required");
    this.deserializer = Objects.requireNonNull(
        deserializer, "Integration Event deserializer is required");
    this.nameMapping = Objects.requireNonNull(
        nameMapping, "Integration Event name mapping is required");
  }

  /**
   * Tram-compatible basic factory shape.
   *
   * <p>The consumer group defaults to the stable subscriber ID. The returned dispatcher is already
   * subscribed.
   */
  public IntegrationEventDispatcher make(
      String subscriberId,
      IntegrationEventHandlers handlers
  ) {
    if (subscriberId == null || subscriberId.isBlank() || handlers == null) {
      throw new IllegalArgumentException("Integration Event dispatcher fields are required");
    }
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializer, handlers, nameMapping);
    MessageSubscription subscription = messageConsumer.subscribe(
        subscriberId,
        handlers.destinations(),
        dispatcher);
    Objects.requireNonNull(subscription, "Message consumer returned no subscription");
    return dispatcher;
  }

  /** Additive overload for a consumer group identity that differs from the Inbox subscriber ID. */
  public IntegrationEventDispatcher make(
      String subscriberId,
      IntegrationEventHandlers handlers,
      MessageSubscriptionOptions options
  ) {
    if (subscriberId == null || subscriberId.isBlank() || handlers == null || options == null) {
      throw new IllegalArgumentException("Integration Event dispatcher fields are required");
    }
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializer, handlers, nameMapping);
    MessageSubscription subscription = messageConsumer.subscribe(
        subscriberId,
        handlers.destinations(),
        dispatcher,
        options);
    Objects.requireNonNull(subscription, "Message consumer returned no subscription");
    return dispatcher;
  }

  /**
   * Additive typed-dispatch overload for shared-channel unhandled-event policy and group identity.
   */
  public IntegrationEventDispatcher make(
      String subscriberId,
      IntegrationEventHandlers handlers,
      IntegrationEventDispatcherOptions options
  ) {
    if (subscriberId == null || subscriberId.isBlank() || handlers == null || options == null) {
      throw new IllegalArgumentException("Integration Event dispatcher fields are required");
    }
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializer, handlers, nameMapping, options);
    MessageSubscription subscription = messageConsumer.subscribe(
        subscriberId,
        handlers.destinations(),
        dispatcher,
        options.subscriptionOptions());
    Objects.requireNonNull(subscription, "Message consumer returned no subscription");
    return dispatcher;
  }
}
