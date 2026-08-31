package com.flowzati.archone.inventory.allocation.application.service;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationAssignmentCandidate;
import com.flowzati.archone.inventory.allocation.application.store.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.service.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared initial/wake pipeline: select one group, plan purely, then cross the atomic assignment boundary. */
@Component
public class StockOperationAssignmentCoordinator {

    private final StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore;
    private final StockAllocationSupplyStore stockAllocationSupplyStore;
    private final StockAllocationPlanner planner;
    private final StockAllocationCommitter allocationCommitter;
    private final BusinessClock businessClock;

    public StockOperationAssignmentCoordinator(
            StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore,
            StockAllocationSupplyStore stockAllocationSupplyStore,
            StockAllocationPlanner planner,
            StockAllocationCommitter allocationCommitter,
            BusinessClock businessClock) {
        this.stockOperationAssignmentCandidateStore = stockOperationAssignmentCandidateStore;
        this.stockAllocationSupplyStore = stockAllocationSupplyStore;
        this.planner = planner;
        this.allocationCommitter = allocationCommitter;
        this.businessClock = businessClock;
    }

    public Optional<StockOperationAssignmentResult> tryAssign(UUID stockOperationId) {
        // 初次配貨只選指定 Operation；補貨喚醒則走 tryAssignNext。
        return attempt(stockOperationAssignmentCandidateStore.findByOperationId(stockOperationId));
    }

    public Optional<StockOperationAssignmentResult> tryAssignNext(AssignmentQueueKey queueKey) {
        return stockOperationAssignmentCandidateStore.findNext(queueKey).flatMap(this::attempt);
    }

    private Optional<StockOperationAssignmentResult> attempt(StockOperationAssignmentCandidate candidate) {
        if (candidate.predecessor().isPresent()) {
            // 共享 SKU 的較早 Operation 尚未離隊，後單不得越過它搶貨。
            return Optional.empty();
        }
        StockOperationDemand demand = candidate.demand();
        LocalDate today = businessClock.today();

        // Focused Store 提供不加鎖的 immutable FEFO supply，讓 Planner 保持純計算。
        StockAllocationSupply supply = stockAllocationSupplyStore.findBySku(
                demand.ownerId(), demand.fromLocationId(), demand.skuCodes(), today);
        StockAllocationProposal proposal = planner.plan(demand, supply);
        if (proposal.isReady()) {
            // 只有完整 Proposal 才進入鎖定、重驗與寫入的原子邊界。
            StockOperationAssignmentResult commit =
                    allocationCommitter.commit(proposal, today, businessClock.instant());
            return Optional.of(commit);
        }
        // SHIP_COMPLETE 缺貨時不做部分預留，保留 CONFIRMED 等待喚醒。
        return Optional.empty();
    }
}
