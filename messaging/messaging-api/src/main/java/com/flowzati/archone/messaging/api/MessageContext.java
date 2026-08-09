package com.flowzati.archone.messaging.api;

/** Transport-neutral context for one delivery attempt. */
public record MessageContext(String subscriberId, String logicalChannel, int processingAttempt) {

  public MessageContext {
    if (isBlank(subscriberId) || isBlank(logicalChannel) || processingAttempt < 1) {
      throw new IllegalArgumentException("Message context fields are required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
