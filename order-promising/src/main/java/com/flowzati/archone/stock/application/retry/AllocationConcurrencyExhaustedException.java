package com.flowzati.archone.stock.application.retry;

/** 代表樂觀鎖衝突在重試策略內沒有收斂。 */
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
