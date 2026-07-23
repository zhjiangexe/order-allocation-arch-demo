package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;

public interface AllocationSelector {

  List<Order> selectOrders(List<Order> candidates, AllocationRequest request);

  static AllocationSelector strictFifo() {
    return contextual(new StrictFifoAllocationPolicy(), new BasicAllocationContextFactory());
  }

  static AllocationSelector maximizeFulfilledOrders() {
    return contextual(new MaximizeFulfilledOrdersPolicy(), new BasicAllocationContextFactory());
  }

  static <C extends AllocationContext> AllocationSelector contextual(
      AllocationPolicy<C> policy,
      AllocationContextFactory<C> contextFactory
  ) {
    return (candidates, request) -> {
      C context = contextFactory.create(request);
      return policy.selectOrders(candidates, context);
    };
  }
}
