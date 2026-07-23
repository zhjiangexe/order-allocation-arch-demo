package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.ordering.domain.model.Order;
import java.util.List;

public interface AllocationPolicy<C extends AllocationContext> {

  List<Order> selectOrders(List<Order> candidates, C context);
}
