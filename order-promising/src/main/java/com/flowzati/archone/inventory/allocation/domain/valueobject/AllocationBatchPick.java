package com.flowzati.archone.inventory.allocation.domain.valueobject;

import java.util.UUID;

/** Immutable allocation decision for one demand-line × stock-quant pair. */
public record AllocationBatchPick(
    UUID allocationDemandId,
    UUID allocationDemandLineId,
    UUID stockQuantId,
    int quantity
) {

  public AllocationBatchPick {
    if (allocationDemandId == null || allocationDemandLineId == null || stockQuantId == null) {
      throw new IllegalArgumentException("An allocation pick requires allocation-owned identities");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Pick quantity must be positive");
    }
  }
}
