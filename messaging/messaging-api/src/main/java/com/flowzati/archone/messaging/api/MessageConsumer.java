package com.flowzati.archone.messaging.api;

import java.util.Set;

/** Subscribes a generic handler without exposing a broker-specific listener API. */
public interface MessageConsumer {

  /**
   * Tram-shaped basic API; the consumer group defaults to {@code subscriberId}.
   *
   * <p>The returned lifecycle handle may be ignored when container lifecycle is owned by the
   * runtime, so callers can still use this method as a plain subscription statement.
   */
  MessageSubscription subscribe(
      String subscriberId,
      Set<String> channels,
      MessageHandler handler
  );

  /** Additive Archone extension that keeps broker delivery identity separate from Inbox scope. */
  MessageSubscription subscribe(
      String subscriberId,
      Set<String> channels,
      MessageHandler handler,
      MessageSubscriptionOptions options
  );
}
