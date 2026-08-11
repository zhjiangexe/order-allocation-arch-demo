package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.StockMove;
import java.util.List;
import java.util.UUID;

/** 一張剛完成 allocation 的需求與其 committed moves，供 transaction owner 建立 handoff fact。 */
public record AssignedDemand(Demand demand, List<StockMove> moves) {

  public AssignedDemand {
    if (demand == null || moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("Assigned demand requires demand and moves");
    }
    moves = List.copyOf(moves);
    UUID allocationId = moves.getFirst().getPickingId();
    if (allocationId == null || moves.stream()
        .anyMatch(move -> !allocationId.equals(move.getPickingId()))) {
      throw new IllegalArgumentException("Assigned demand moves must share one allocation ID");
    }
  }
}
