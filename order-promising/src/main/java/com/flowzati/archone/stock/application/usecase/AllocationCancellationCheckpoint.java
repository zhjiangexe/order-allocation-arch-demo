package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.stock.domain.model.AllocationCancellationState;
import com.flowzati.archone.stock.domain.model.AllocationDemand;

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
