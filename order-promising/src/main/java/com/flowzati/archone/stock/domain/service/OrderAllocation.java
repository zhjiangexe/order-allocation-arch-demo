package com.flowzati.archone.stock.domain.service;

import com.flowzati.archone.stock.domain.model.Demand;
import java.util.List;

/** 補貨喚醒時，一筆被滿足的需求與它取用的批。 */
public record OrderAllocation(Demand demand, List<BatchPick> picks) {

  public OrderAllocation {
    if (demand == null) {
      throw new IllegalArgumentException("Demand is required");
    }
    picks = List.copyOf(picks);
    if (picks.isEmpty()) {
      throw new IllegalArgumentException("An allocated order must carry picks");
    }
  }
}
