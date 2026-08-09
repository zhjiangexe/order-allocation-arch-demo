package com.flowzati.archone.messaging.api;

/** Idempotently stoppable handle returned by a message consumer implementation. */
public interface MessageSubscription extends AutoCloseable {

  boolean isRunning();

  void stop();

  @Override
  default void close() {
    stop();
  }
}
