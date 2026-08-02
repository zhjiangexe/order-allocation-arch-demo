package com.flowzati.archone.stock.domain.service.selector;

import com.flowzati.archone.stock.domain.service.AllocationRequest;
import com.flowzati.archone.stock.domain.service.selector.context.BasicAllocationContextFactory;
import com.flowzati.archone.stock.domain.service.selector.policy.MaximizeFulfilledOrdersPolicy;
import com.flowzati.archone.stock.domain.service.selector.policy.StrictFifoAllocationPolicy;
import com.flowzati.archone.stock.domain.model.Demand;

import java.util.List;

@FunctionalInterface
public interface AllocationSelector {

  List<Demand> select(List<Demand> candidates, AllocationRequest request);

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
