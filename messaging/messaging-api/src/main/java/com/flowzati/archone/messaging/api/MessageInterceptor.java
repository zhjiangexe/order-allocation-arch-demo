package com.flowzati.archone.messaging.api;

/**
 * Tram-style send/receive lifecycle hooks without framework dependencies.
 *
 * <p>{@link #preSend(Message)} returns a message because {@link Message} is immutable. Lifecycle
 * orchestrators must preserve reserved headers and report the original failure to post hooks.
 */
public interface MessageInterceptor {

  default Message preSend(Message message) {
    return message;
  }

  default void postSend(Message message, Throwable failure) {
  }

  default void preReceive(Message message) {
  }

  default void preHandle(Message message) {
  }

  default void postHandle(Message message, Throwable failure) {
  }

  default void postReceive(Message message, Throwable failure) {
  }
}
