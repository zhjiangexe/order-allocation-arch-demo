package com.flowzati.archone.inventory.allocation.application.command;

import java.util.UUID;

/**
 * 釋放指定訂單目前 ACTIVE reservation 的業務意圖。
 */
public record CancelMovementsCommand(UUID orderId, UUID cancellationOperationId) {

  public CancelMovementsCommand {
    if (orderId == null || cancellationOperationId == null) {
      throw new IllegalArgumentException("Order ID and cancellation operation ID are required");
    }
  }

  /** Compatibility path; production order events use their event id as the stable operation id. */
  public CancelMovementsCommand(UUID orderId) {
    this(orderId, orderId);
  }
}
