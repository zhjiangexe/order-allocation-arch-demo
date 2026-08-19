package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;

/** Snapshot returned after a short local cancellation checkpoint transaction. */
public record AllocationCancellationCheckpoint(
    AllocationDemand demand,
    AllocationCancellationState state
) {
  public AllocationCancellationCheckpoint {
    if (demand == null || state == null) {
      throw new IllegalArgumentException("Cancellation checkpoint requires demand and state");
    }
  }
}
