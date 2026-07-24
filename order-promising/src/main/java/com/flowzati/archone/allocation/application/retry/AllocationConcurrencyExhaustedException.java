package com.flowzati.archone.allocation.application.retry;

/** Signals that an optimistic-lock conflict did not converge within the retry policy. */
public class AllocationConcurrencyExhaustedException extends RuntimeException {

  private final AllocationRetryContext context;
  private final int attempts;

  public AllocationConcurrencyExhaustedException(
      AllocationRetryContext context,
      int attempts,
      Throwable cause
  ) {
    super("Allocation concurrency retry exhausted after " + attempts + " attempts for order "
        + context.orderId(), cause);
    this.context = context;
    this.attempts = attempts;
  }

  public AllocationRetryContext context() {
    return context;
  }

  public int attempts() {
    return attempts;
  }
}
