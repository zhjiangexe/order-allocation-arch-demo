package com.flowzati.archone.allocation.domain.service;

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
    smallestFirst.sort(Comparator.comparingInt(Order::getQuantity));

    int remaining = context.availableToPromise();
    List<Order> selected = new ArrayList<>();
    for (Order order : smallestFirst) {
      if (order.getQuantity() > remaining) {
        break;
      }
      selected.add(order);
      remaining -= order.getQuantity();
    }
    return List.copyOf(selected);
  }
}
