package com.flowzati.archone.ordering.domain.repository;

import com.flowzati.archone.ordering.domain.model.Order;

import java.util.List;
import java.util.UUID;

public interface OrderRepository {
  void save(Order placedOrder);

  Order findById(UUID orderId);

  List<Order> getPendingBySku(String sku);
}
