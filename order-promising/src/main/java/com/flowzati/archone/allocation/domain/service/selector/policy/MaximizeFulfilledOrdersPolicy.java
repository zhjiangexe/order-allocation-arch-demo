package com.flowzati.archone.allocation.domain.service.selector.policy;

import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.allocation.domain.model.Demand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MaximizeFulfilledOrdersPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Demand> selectOrders(
      List<Demand> candidates,
      BasicAllocationContext context
  ) {
    List<Demand> smallestFirst = new ArrayList<>(candidates);
    smallestFirst.sort(Comparator.comparingInt(candidate -> candidate.demandFor(context.skuCode())));

    int remaining = context.availableToPromise();
    List<Demand> selected = new ArrayList<>();
    for (Demand candidate : smallestFirst) {
      int required = candidate.demandFor(context.skuCode());
      if (required > remaining) {
        break;
      }
      selected.add(candidate);
      remaining -= required;
    }
    return List.copyOf(selected);
  }
}
