package com.flowzati.archone.messaging.consumer.common;

/**
 * Non-retryable failure while converting a transport record into the generic message envelope.
 *
 * <p>The original exception remains available as the cause. Keeping this stage explicit lets an
 * application distinguish malformed transport data from a failure thrown by its message handler.
 */
public final class MessageMappingException extends IllegalArgumentException {

  public MessageMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
