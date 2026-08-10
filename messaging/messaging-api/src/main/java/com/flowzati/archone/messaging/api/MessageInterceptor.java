package com.flowzati.archone.messaging.api;

/**
 * Tram-style send/receive lifecycle hooks without framework dependencies.
 *
 * <p>{@link #preSend(Message)} returns a message because {@link Message} is immutable. Contextual
 * overloads let infrastructure observe routing and delivery-attempt metadata while their defaults
 * retain compatibility with existing interceptors. Implementors normally override either the
 * legacy or contextual form of a lifecycle hook, not both. Lifecycle orchestrators must preserve
 * reserved headers and report the original failure to post hooks.
 */
public interface MessageInterceptor {

  default Message preSend(Message message) {
    return message;
  }

  default Message preSend(Message message, MessagePublicationContext context) {
    return preSend(message);
  }

  default void postSend(Message message, Throwable failure) {
  }

  default void postSend(
      Message message,
      MessagePublicationContext context,
      Throwable failure
  ) {
    postSend(message, failure);
  }

  default void preReceive(Message message) {
  }

  default void preReceive(Message message, MessageContext context) {
    preReceive(message);
  }

  default void preHandle(Message message) {
  }

  default void preHandle(Message message, MessageContext context) {
    preHandle(message);
  }

  default void postHandle(Message message, Throwable failure) {
  }

  default void postHandle(Message message, MessageContext context, Throwable failure) {
    postHandle(message, failure);
  }

  default void postReceive(Message message, Throwable failure) {
  }

  default void postReceive(Message message, MessageContext context, Throwable failure) {
    postReceive(message, failure);
  }
}
