package com.flowzati.archone.stock.allocation.application.command;

import java.util.UUID;

/** Stable idempotency identity for a source-agnostic allocation cancellation request. */
public record CancelAllocationDemandCommand(
    UUID allocationDemandId,
    UUID cancellationOperationId
) {
  public CancelAllocationDemandCommand {
    if (allocationDemandId == null || cancellationOperationId == null) {
      throw new IllegalArgumentException("Allocation demand and cancellation operation ids are required");
    }
  }
}
