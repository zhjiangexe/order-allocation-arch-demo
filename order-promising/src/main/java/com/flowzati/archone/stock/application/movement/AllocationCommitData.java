package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.service.AllocationDemandPlan;
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
