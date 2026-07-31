package com.flowzati.archone.allocation.domain.service.selector.policy;

import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.allocation.domain.model.Demand;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StrictFifoAllocationPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Demand> selectOrders(
      List<Demand> candidates,
      BasicAllocationContext context
  ) {
    requireValidCandidates(candidates);

    int remaining = context.availableToPromise();
    List<Demand> selected = new ArrayList<>();
    for (Demand candidate : candidates) {
      int required = candidate.demandFor(context.skuCode());
      if (required > remaining) {
        break;
      }
      selected.add(candidate);
      remaining -= required;
    }
    return List.copyOf(selected);
  }

  private static void requireValidCandidates(List<Demand> candidates) {
    if (candidates == null) {
      throw new IllegalArgumentException("Allocation candidates are required");
    }
    if (candidates.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Allocation candidate cannot be null");
    }
  }
}
