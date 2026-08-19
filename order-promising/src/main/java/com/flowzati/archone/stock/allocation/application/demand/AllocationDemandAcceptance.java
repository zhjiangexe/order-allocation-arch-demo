package com.flowzati.archone.stock.allocation.application.demand;

import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import java.util.List;

/** Result of idempotently accepting source demand content. */
public record AllocationDemandAcceptance(
    AllocationDemand demand,
    List<StockMove> moves,
    boolean created
) {

  public AllocationDemandAcceptance {
    if (demand == null || moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("Accepted allocation demand requires execution movements");
    }
    moves = List.copyOf(moves);
  }
}
