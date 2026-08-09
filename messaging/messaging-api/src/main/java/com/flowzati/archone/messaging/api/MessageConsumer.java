package com.flowzati.archone.messaging.api;

/** Subscribes a generic handler without exposing a broker-specific listener API. */
@FunctionalInterface
public interface MessageConsumer {

  MessageSubscription subscribe(
      MessageSubscriptionConfiguration configuration,
      MessageHandler handler
  );
}
