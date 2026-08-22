package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.inventory.allocation.application.event.OrderAllocationCommittedPublicationFactory;
import com.flowzati.archone.inventory.allocation.application.result.AllocationCommitResult;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.service.AllocationDemandPlanner;
import com.flowzati.archone.inventory.allocation.domain.service.AllocationFifoSelector;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandPlan;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
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
 * <p>首次接受、availability wake-up 與 reconciliation 都走這一條路。每次最多 commit 一筆
 * demand，避免一個 transaction 鎖住整條 queue；成功或失敗後由外層 trigger 決定是否再跑下一輪。
 */
@Component
public class PendingDemandAllocator {

    private final AllocationDemandRepository demandRepository;
    private final StockQuantRepository stockQuantRepository;
    private final AllocationFifoSelector demandSelector;
    private final AllocationDemandPlanner demandPlanner;
    private final AllocationCommitter planCommitter;
    private final IntegrationEventPublisher integrationEventPublisher;
    private final AllocationAttemptObserver attemptObserver;

    public PendingDemandAllocator(
            AllocationDemandRepository demandRepository,
            StockQuantRepository stockQuantRepository,
            AllocationFifoSelector demandSelector,
            AllocationDemandPlanner demandPlanner,
            AllocationCommitter planCommitter,
            IntegrationEventPublisher integrationEventPublisher,
            AllocationAttemptObserver attemptObserver) {
        this.demandRepository = demandRepository;
        this.stockQuantRepository = stockQuantRepository;
        this.demandSelector = demandSelector;
        this.demandPlanner = demandPlanner;
        this.planCommitter = planCommitter;
        this.integrationEventPublisher = integrationEventPublisher;
        this.attemptObserver = attemptObserver;
    }

    /** 最多 commit 一筆 demand；successor 交給下一次 bounded invocation 重新評估。 */
    public Optional<AllocationDemand> allocateOne(
            WaitingAllocationScope scope, String triggeringSku, int candidateLimit, LocalDate today, Instant now) {
        // 此次查到的 candidate 都共享 triggeringSku。strict FIFO 下，第一筆若不能通過它的所有 SKU
        // queue，後面的 demand 也不能在 triggering queue 超車；因此實際只需載入 queue head，再加上
        // repository 帶回的所有 shared-SKU predecessor context。多載 candidate 不會改變本輪決策。
        AllocationCandidateBatch batch =
                demandRepository.findPendingCandidates(scope, triggeringSku, Math.min(candidateLimit, 1));

        // 純演算法：candidate 必須在每個 required SKU queue 都是最早的一筆。
        Optional<AllocationDemand> selected = demandSelector.selectFirstEligible(batch);
        if (selected.isEmpty()) {
            attemptObserver.recordBlocked(batch, now);
            return Optional.empty();
        }

        AllocationDemand demand = selected.get();
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
