package com.flowzati.archone.inventory.allocation.application.demand;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import java.util.List;

/** 冪等登記來源 demand 與 outbound execution 的結果。 */
public record AllocationDemandRegistration(
    AllocationDemand demand,
    List<StockMove> moves,
    boolean created
) {

  public AllocationDemandRegistration {
    if (demand == null || moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("Registered allocation demand requires execution movements");
    }
    moves = List.copyOf(moves);
  }
}
