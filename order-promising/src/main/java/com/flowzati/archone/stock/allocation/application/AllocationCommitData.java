package com.flowzati.archone.stock.allocation.application;

import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import com.flowzati.archone.stock.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.stock.inventory.domain.aggregate.StockPool;
import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationDemandPlan;
import java.util.List;

/**
 * 一次 allocation commit 所需的資料快照。
 *
 * <p>這個 record 只保存資料，不查 repository、不做跨模型業務驗證，也不修改任何 aggregate。
 */
record AllocationCommitData(
    AllocationDemandPlan plan,
    AllocationDemand demand,
    List<StockMove> moves,
    List<StockPool> stockPoolsInWriteOrder,
    List<StockPicking> pickings
) {

  AllocationCommitData {
    if (plan == null || demand == null || moves == null
        || stockPoolsInWriteOrder == null || pickings == null) {
      throw new IllegalArgumentException("Allocation commit data must be complete");
    }
    moves = List.copyOf(moves);
    stockPoolsInWriteOrder = List.copyOf(stockPoolsInWriteOrder);
    pickings = List.copyOf(pickings);
  }
}
