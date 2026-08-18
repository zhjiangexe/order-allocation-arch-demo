package com.flowzati.archone.stock.application.demand;

import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.StockMove;
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
