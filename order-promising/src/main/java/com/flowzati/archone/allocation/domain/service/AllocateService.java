package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.ordering.domain.model.Order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AllocateService {

  private final AllocationSelector selector;

  public AllocateService(AllocationSelector selector) {
    this.selector = selector;
  }

  public AllocateService() {
    this(AllocationSelector.strictFifo());
  }

  public AllocationOutcome allocate(Order order, StockPool stockPool, Instant now) {
    requireMatchingSku(order, stockPool);

    if (stockPool.canReserve(order.getQuantity())) {
      applyAllocation(order, stockPool, now);
      return AllocationOutcome.ALLOCATED;
    }
    return AllocationOutcome.INSUFFICIENT_ATP;
  }

  public List<Order> allocateBackorders(
      List<Order> candidates,
      StockPool stockPool,
      Instant now
  ) {
    candidates.forEach(order -> requireMatchingSku(order, stockPool));
    AllocationRequest request = new AllocationRequest(
        stockPool.getId(),
        stockPool.getSku(),
        stockPool.availableToPromise(),
        now
    );

    List<Order> selected = selector.selectOrders(candidates, request);
    List<Order> allocated = new ArrayList<>();
    for (Order order : selected) {
      applyAllocation(order, stockPool, now);
      allocated.add(order);
    }

    return allocated;
  }

  private void applyAllocation(Order order, StockPool stockPool, Instant now) {
    order.markAllocated(now);
    stockPool.reserve(order.getQuantity());
  }

  private void requireMatchingSku(Order order, StockPool stockPool) {
    if (!Objects.equals(order.getSku(), stockPool.getSku())) {
      throw new IllegalArgumentException("Order and stock pool SKU must match");
    }
  }
}
