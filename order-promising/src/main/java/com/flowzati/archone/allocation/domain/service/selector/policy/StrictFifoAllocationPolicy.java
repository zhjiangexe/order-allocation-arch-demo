package com.flowzati.archone.allocation.domain.service.selector.policy;

import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.ordering.domain.model.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StrictFifoAllocationPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Order> selectOrders(
      List<Order> candidates,
      BasicAllocationContext context
  ) {
    requireValidCandidates(candidates);

    int remaining = context.availableToPromise();
    List<Order> selected = new ArrayList<>();
    for (Order order : candidates) {
      if (order.getQuantity() > remaining) {
        break;
      }
      selected.add(order);
      remaining -= order.getQuantity();
    }
    return List.copyOf(selected);
  }

  private static void requireValidCandidates(List<Order> candidates) {
    if (candidates == null) {
      throw new IllegalArgumentException("Allocation candidates are required");
    }
    if (candidates.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Allocation candidate cannot be null");
    }
  }
}
