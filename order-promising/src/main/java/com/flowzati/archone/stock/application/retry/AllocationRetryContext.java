package com.flowzati.archone.stock.application.retry;

import java.util.UUID;

/** Diagnostic context retained when an allocation transaction must be retried. */
public record AllocationRetryContext(
    String operation,
    UUID eventId,
    String orderId,
    String sku
) {

  public AllocationRetryContext {
    if (operation == null || operation.isBlank() || eventId == null) {
      throw new IllegalArgumentException("Retry context operation and event ID are required");
    }
    orderId = orderId == null || orderId.isBlank() ? "unknown" : orderId;
    sku = sku == null || sku.isBlank() ? "unknown" : sku;
  }
}
