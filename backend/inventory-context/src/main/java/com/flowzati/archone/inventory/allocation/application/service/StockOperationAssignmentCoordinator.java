package com.flowzati.archone.inventory.allocation.application.service;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.application.store.OwnerAllocationPolicyStore;
import com.flowzati.archone.inventory.allocation.application.store.StockAllocationSupplyStore;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.allocation.domain.service.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 共用配貨流程：選取需求、檢查順位、計算分配，再交由 Committer 原子寫入。
 * 指定需求與補貨喚醒共用相同的規劃／提交邏輯；每次最多成功配一張 StockOperation。
 */
@Component
public class StockOperationAssignmentCoordinator {
    private static final Logger log = LoggerFactory.getLogger(StockOperationAssignmentCoordinator.class);

    private final StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore;
    private final OwnerAllocationPolicyStore ownerAllocationPolicyStore;
    private final StockAllocationSupplyStore stockAllocationSupplyStore;
    private final StockAllocationPlanner planner;
    private final StockAllocationCommitter allocationCommitter;
    private final BusinessClock businessClock;

    public StockOperationAssignmentCoordinator(
        StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore,
        OwnerAllocationPolicyStore ownerAllocationPolicyStore,
        StockAllocationSupplyStore stockAllocationSupplyStore,
        StockAllocationPlanner planner,
        StockAllocationCommitter allocationCommitter,
        BusinessClock businessClock) {
        this.stockOperationAssignmentCandidateStore = stockOperationAssignmentCandidateStore;
        this.ownerAllocationPolicyStore = ownerAllocationPolicyStore;
        this.stockAllocationSupplyStore = stockAllocationSupplyStore;
        this.planner = planner;
        this.allocationCommitter = allocationCommitter;
        this.businessClock = businessClock;
    }

    /**
     * 嘗試配貨指定需求；無可配需求、被前序需求阻擋或庫存不足時，回傳空結果。
     */
    public Optional<StockOperationAssignmentResult> tryAssign(UUID stockOperationId) {
        // 初次配貨只選指定 Operation；補貨喚醒則走 tryAssignNext。
        var demand = stockOperationAssignmentCandidateStore.findDemand(stockOperationId);
        // 沒有可配需求就略過，包含不存在、已處理或不符合配貨條件的 Operation。
        if (demand.isEmpty()) {
            return Optional.empty();
        }
        StockOperationDemand selectedDemand = demand.orElseThrow();
        // 本次依貨主設定判斷順位；提交時不再讀取設定或重新排序。
        AllocationSequencePolicy policy = ownerAllocationPolicyStore.find(selectedDemand.ownerId());
        return assignIfEligible(selectedDemand, policy);
    }

    /**
     * 從指定「貨主＋來源位置＋SKU」佇列選一張需求；是否繼續下一張由呼叫端決定。
     */
    public Optional<StockOperationAssignmentResult> tryAssignNext(AssignmentQueueKey queueKey) {
        // 先選隊首，再依同一策略檢查前序需求；沒有候選時，不查前序需求或進入規劃。
        AllocationSequencePolicy policy = ownerAllocationPolicyStore.find(queueKey.ownerId());
        return stockOperationAssignmentCandidateStore
            .findNext(queueKey, policy)
            .flatMap(demand -> assignIfEligible(demand, policy));
    }

    /**
     * 兩種入口共用的單張配貨流程；空結果表示這次沒有新增配貨。
     */
    private Optional<StockOperationAssignmentResult> assignIfEligible(
        StockOperationDemand demand, AllocationSequencePolicy policy) {
        // 規劃前先檢查順位，避免替被阻擋的需求查庫存、計算分配。
        Optional<StockOperationPredecessor> predecessor =
            stockOperationAssignmentCandidateStore.findPredecessor(demand, policy);
        if (predecessor.isPresent()) {
            log.atDebug()
                .addKeyValue("stockOperationId", demand.stockOperationId())
                .addKeyValue("predecessor", predecessor.orElseThrow())
                .log("Assignment blocked by earlier operation");
            // 優先需求即使缺貨，後單也不能因為需求量較小而先取用共享庫存。
            return Optional.empty();
        }
        LocalDate today = businessClock.today();

        // 一次讀取整張需求涉及的 SKU 供給，不在這裡鎖庫存或預留數量。
        // Planner 依 FEFO 計算分配；這份供給可能在提交前被其他交易改變。
        StockAllocationSupply supply =
            stockAllocationSupplyStore.findBySku(demand.ownerId(), demand.fromLocationId(), demand.skuCodes(), today);
        StockAllocationProposal proposal = planner.plan(demand, supply);
        if (!proposal.isReady()) {
            log.atDebug()
                .addKeyValue("stockOperationId", demand.stockOperationId())
                .addKeyValue(
                    "missingQuantities", proposal.missingQuantities().asMap())
                .log("Assignment deferred due to insufficient stock");
            // SHIP_COMPLETE 缺貨時不做部分預留，保留 CONFIRMED 等待喚醒。
            return Optional.empty();
        }

        // 足量才提交：Committer 鎖定並重驗需求版本、庫存及完整覆蓋，原子完成預留與事件發布。
        // 順位已在規劃前判斷；此時不因晚到的優先需求而讓位。
        StockOperationAssignmentResult result = allocationCommitter.commit(proposal, today, businessClock.instant());
        return Optional.of(result);
    }
}
