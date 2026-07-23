package com.flowzati.archone.allocation.domain.service.selector;

import com.flowzati.archone.allocation.domain.service.AllocationRequest;
import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContextFactory;
import com.flowzati.archone.allocation.domain.service.selector.policy.MaximizeFulfilledOrdersPolicy;
import com.flowzati.archone.allocation.domain.service.selector.policy.StrictFifoAllocationPolicy;
import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;

@FunctionalInterface
public interface AllocationSelector {

  List<Order> select(List<Order> candidateOrders, AllocationRequest request);

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
    return (candidateOrders, request) -> {
      C context = contextFactory.create(request);
      return policy.selectOrders(candidateOrders, context);
    };
  }
}
