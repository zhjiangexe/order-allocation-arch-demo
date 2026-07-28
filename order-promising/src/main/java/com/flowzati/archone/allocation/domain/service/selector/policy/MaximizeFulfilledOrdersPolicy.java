package com.flowzati.archone.allocation.domain.service.selector.policy;

import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.ordering.domain.model.Order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MaximizeFulfilledOrdersPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Order> selectOrders(
      List<Order> candidates,
      BasicAllocationContext context
  ) {
    List<Order> smallestFirst = new ArrayList<>(candidates);
    smallestFirst.sort(Comparator.comparingInt(order -> order.getDemandFor(context.skuCode())));

    int remaining = context.availableToPromise();
    List<Order> selected = new ArrayList<>();
    for (Order order : smallestFirst) {
      int demand = order.getDemandFor(context.skuCode());
      if (demand > remaining) {
        break;
      }
      selected.add(order);
      remaining -= demand;
    }
    return List.copyOf(selected);
  }
}
