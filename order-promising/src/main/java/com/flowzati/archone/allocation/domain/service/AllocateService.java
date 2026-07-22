package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.ordering.domain.model.Order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AllocateService {
  private final AllocationPolicy policy;

  public AllocateService(AllocationPolicy policy) {
    this.policy = policy;
  }

  /**
   * 核心業務邏輯：嘗試將庫存分配給指定的訂單列表
   */
  public List<Order> drain(List<Order> ordersToProcess, StockPool stockPool, Instant now) {
    List<Order> allocatedOrders = new ArrayList<>();
    for (Order order : ordersToProcess) {
      if (stockPool.tryAllocate(order.getQuantity())) {
        order.markAllocated(now);
        allocatedOrders.add(order);
      }
    }
    return allocatedOrders;
  }

}
