package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandPlan;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
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
        List<StockQuant> stockQuantsInWriteOrder,
        List<StockPicking> pickings) {

    AllocationCommitData {
        if (plan == null || demand == null || moves == null || stockQuantsInWriteOrder == null || pickings == null) {
            throw new IllegalArgumentException("Allocation commit data must be complete");
        }
        moves = List.copyOf(moves);
        stockQuantsInWriteOrder = List.copyOf(stockQuantsInWriteOrder);
        pickings = List.copyOf(pickings);
    }
}
