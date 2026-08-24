package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.inventory.allocation.application.event.OrderAllocationCommittedPublicationFactory;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.service.AllocationDemandPlanner;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandPlan;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.balance.domain.valueobject.AllocatableBatches;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Demand-first allocation 的共用 allocator。
 *
 * <p>首次接受直接使用剛保存的 demand 只嘗試該筆需求；availability wake-up 與 reconciliation 則以 queue key
 * 嘗試隊首。兩者共用 FIFO、planning 與 commit 流程，而且每次最多 commit 一筆 demand，避免一個
 * transaction 鎖住整條 queue。
 */
@Component
public class PendingDemandAllocator {

    private final PendingDemandSelection pendingDemandSelection;
    private final StockQuantRepository stockQuantRepository;
    private final AllocationDemandPlanner demandPlanner;
    private final AllocationCommitter planCommitter;
    private final IntegrationEventPublisher integrationEventPublisher;
    private final AllocationAttemptObserver attemptObserver;

    public PendingDemandAllocator(
            PendingDemandSelection pendingDemandSelection,
            StockQuantRepository stockQuantRepository,
            AllocationDemandPlanner demandPlanner,
            AllocationCommitter planCommitter,
            IntegrationEventPublisher integrationEventPublisher,
            AllocationAttemptObserver attemptObserver) {
        this.pendingDemandSelection = pendingDemandSelection;
        this.stockQuantRepository = stockQuantRepository;
        this.demandPlanner = demandPlanner;
        this.planCommitter = planCommitter;
        this.integrationEventPublisher = integrationEventPublisher;
        this.attemptObserver = attemptObserver;
    }

    /** 只嘗試指定 demand；若它不是每條 required-SKU queue 的 head，就維持 PENDING。 */
    public Optional<AllocationDemand> tryAllocateDemand(AllocationDemand candidate, LocalDate today, Instant now) {
        if (candidate.status() != AllocationDemandStatus.PENDING) {
            return Optional.empty();
        }
        PendingDemandQueuePosition position = pendingDemandSelection.positionOf(candidate);
        return tryCommitIfAtQueueHeads(position, today, now);
    }

    /** 最多 commit 指定 queue 的一筆 head demand；successor 留給下一次 bounded invocation。 */
    public Optional<AllocationDemand> tryAllocateQueueHead(
            AllocationDemandQueueKey queueKey, LocalDate today, Instant now) {
        // 先找觸發 queue 的第一筆 pending demand，再只載入它每個 required SKU 當下可見的 queue head。
        // 任一 queue head 不是 candidate 自己，就表示它仍須等待，不能從其他 SKU queue 插隊。
        return pendingDemandSelection
                .findQueueHead(queueKey)
                .flatMap(position -> tryCommitIfAtQueueHeads(position, today, now));
    }

    private Optional<AllocationDemand> tryCommitIfAtQueueHeads(
            PendingDemandQueuePosition position, LocalDate today, Instant now) {
        if (!position.isHeadOfEveryRequiredQueue()) {
            attemptObserver.recordBlocked(position, now);
            return Optional.empty();
        }

        AllocationDemand demand = position.demand();
        // 一次載入這筆 demand 的全部 SKU；每個 SKU group 都已由 repository 依 FEFO 排好。
        AllocatableBatches stock = stockQuantRepository.findAllocatableBatchesBySku(
                demand.ownerId(), demand.locationId(), demand.totalsBySku().keySet(), today);
        AllocationDemandPlan plan = demandPlanner.plan(demand, stock);
        if (!plan.isReadyToCommit()) {
            // Ship-complete/all-or-nothing：任一 SKU 不足就不保留任何批次，demand 繼續 PENDING。
            return Optional.empty();
        }

        // Committer 重新驗證資料仍一致後才 reserve；成功才依 completion result 發布 Integration Events。
        Optional<AllocationCommitResult> commit = planCommitter.commit(plan, now);
        if (commit.isPresent()) {
            integrationEventPublisher.publish(OrderAllocationCommittedPublicationFactory.create(commit.get()));
            return Optional.of(demand);
        }
        return Optional.empty();
    }
}
