package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;

import java.util.HashSet;
import java.util.List;

/** Triggered candidates plus every earlier shared-SKU demand required for FIFO evaluation. */
public record AllocationCandidateBatch(
    List<AllocationDemand> candidates,
    List<AllocationDemand> fifoContext
) {

  public AllocationCandidateBatch {
    if (candidates == null || fifoContext == null) {
      throw new IllegalArgumentException("Allocation candidate batch is required");
    }
    candidates = List.copyOf(candidates);
    fifoContext = List.copyOf(fifoContext);
    HashSet<java.util.UUID> contextIds = new HashSet<>();
    fifoContext.forEach(demand -> contextIds.add(demand.id()));
    if (candidates.stream().anyMatch(candidate -> !contextIds.contains(candidate.id()))) {
      throw new IllegalArgumentException("FIFO context must include every triggered candidate");
    }
  }

  public static AllocationCandidateBatch empty() {
    return new AllocationCandidateBatch(List.of(), List.of());
  }
}
