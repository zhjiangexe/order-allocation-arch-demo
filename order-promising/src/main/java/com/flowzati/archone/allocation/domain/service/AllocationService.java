package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.service.selector.AllocationSelector;
import com.flowzati.archone.ordering.domain.model.Order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class AllocationService {

  private final AllocationSelector allocationSelector;

  public AllocationService(AllocationSelector allocationSelector) {
    this.allocationSelector = allocationSelector;
  }

  public AllocationService() {
    this(AllocationSelector.strictFifo());
  }

  public AllocationOutcome allocate(Order order, StockPool stockPool, Instant now) {
    requireDemandIsEntirelyInThisPool(order, stockPool);

    if (stockPool.canReserve(order.getDemandFor(stockPool.getSku()))) {
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
    candidates.forEach(order -> requireDemandIsEntirelyInThisPool(order, stockPool));
    AllocationRequest request = new AllocationRequest(
        stockPool.getId(),
        stockPool.getSku(),
        stockPool.availableToPromise(),
        now
    );

    List<Order> selected = allocationSelector.select(candidates, request);
    List<Order> allocated = new ArrayList<>();
    for (Order order : selected) {
      applyAllocation(order, stockPool, now);
      allocated.add(order);
    }

    return allocated;
  }

  private void applyAllocation(Order order, StockPool stockPool, Instant now) {
    order.markAllocated(now);
    stockPool.reserve(order.getDemandFor(stockPool.getSku()));
  }

  /**
   * 這張單的需求必須<strong>恰好</strong>落在這一個庫存池上。
   *
   * <p>檢查的是「需求的 SKU 集合等於 {@code {pool.sku}}」，而不是「包含 pool.sku」。寫成
   * 包含的話，一張跨多個 SKU 的訂單會通過檢查，然後只扣其中一個 SKU 的量，而整張單被標為
   * 已配——那是靜默的錯，不會有任何測試失敗，因為單行訂單下兩種寫法完全等價。
   *
   * <p>一次配貨只取得一個庫存池，所以跨 SKU 的訂單在這一層就必須被擋下；讓它能被配貨屬於
   * 後續 change 的工作（見 roadmap R8）。
   */
  private void requireDemandIsEntirelyInThisPool(Order order, StockPool stockPool) {
    if (!Objects.equals(order.getDemand().keySet(), Set.of(stockPool.getSku()))) {
      throw new IllegalArgumentException("Order and stock pool SKU must match");
    }
  }
}
