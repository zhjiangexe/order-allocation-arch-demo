package com.flowzati.archone.messaging.spring.optimisticlocking;

import java.util.UUID;

/** Signals that bounded local optimistic-lock attempts were exhausted. */
public final class OptimisticLockingRetryExhaustedException extends RuntimeException {

  private final String subscriberId;
  private final UUID messageId;
  private final int attempts;

  public OptimisticLockingRetryExhaustedException(
      String subscriberId,
      UUID messageId,
      int attempts,
      Throwable cause
  ) {
    super("Optimistic-lock retry exhausted after " + attempts + " attempts", cause);
    if (subscriberId == null || subscriberId.isBlank() || messageId == null || attempts < 1) {
      throw new IllegalArgumentException("Optimistic-lock retry failure fields are required");
    }
    this.subscriberId = subscriberId;
    this.messageId = messageId;
    this.attempts = attempts;
  }

  public String subscriberId() {
    return subscriberId;
  }

  public UUID messageId() {
    return messageId;
  }

  public int attempts() {
    return attempts;
  }
}
