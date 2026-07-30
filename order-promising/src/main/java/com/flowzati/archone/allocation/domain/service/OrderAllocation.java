package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.ordering.domain.model.Order;
import java.util.List;

/** 補貨喚醒時，一張被配到的訂單與它取用的批。 */
public record OrderAllocation(Order order, List<BatchPick> picks) {

  public OrderAllocation {
    if (order == null) {
      throw new IllegalArgumentException("Order is required");
    }
    picks = List.copyOf(picks);
    if (picks.isEmpty()) {
      throw new IllegalArgumentException("An allocated order must carry picks");
    }
  }
}
