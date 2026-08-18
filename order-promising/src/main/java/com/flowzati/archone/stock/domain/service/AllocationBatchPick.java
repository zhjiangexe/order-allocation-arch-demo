package com.flowzati.archone.stock.domain.service;

import java.util.UUID;

/** Immutable allocation decision for one demand-line × stock-pool pair. */
public record AllocationBatchPick(
    UUID allocationDemandId,
    UUID allocationDemandLineId,
    UUID stockPoolId,
    int quantity
) {

  public AllocationBatchPick {
    if (allocationDemandId == null || allocationDemandLineId == null || stockPoolId == null) {
      throw new IllegalArgumentException("An allocation pick requires allocation-owned identities");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Pick quantity must be positive");
    }
  }
}
