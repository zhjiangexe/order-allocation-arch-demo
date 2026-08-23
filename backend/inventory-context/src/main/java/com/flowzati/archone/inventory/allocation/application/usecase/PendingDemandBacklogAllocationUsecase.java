package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AllocatePendingDemandCommand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 在有界的全域工作預算內配置 PENDING demands，並兼作 allocation 的 anti-entropy（補漏）機制。
 *
 * <p>每條 pending-demand queue 都交給 {@link PendingDemandAllocationUsecase}，而且各自使用獨立
 * transaction。單一 queue 失敗會留待下一輪再處理，不會阻止其他 queues 繼續配貨。
 */
@Service
public class PendingDemandBacklogAllocationUsecase {

    private static final Logger log = LoggerFactory.getLogger(PendingDemandBacklogAllocationUsecase.class);

    private final AllocationDemandRepository demandRepository;
    private final PendingDemandAllocationUsecase pendingDemandAllocationUsecase;
    private final BusinessClock appClock;
    private final int maxAttemptsPerRun;
    private final Duration maxRunDuration;

    public PendingDemandBacklogAllocationUsecase(
            AllocationDemandRepository demandRepository,
            PendingDemandAllocationUsecase pendingDemandAllocationUsecase,
            BusinessClock appClock,
            @Value("${archone.allocation.reconciliation-scheduler-max-attempts-per-run:"
                            + "${archone.allocation.reconciliation-scheduler-scope-limit:200}}")
                    int maxAttemptsPerRun,
            @Value("${archone.allocation.reconciliation-scheduler-max-run-duration-ms:45000}") long maxRunDurationMs) {
        if (maxAttemptsPerRun <= 0) {
            throw new IllegalArgumentException("Allocation reconciliation attempt limit must be positive");
        }
        if (maxRunDurationMs <= 0) {
            throw new IllegalArgumentException("Allocation reconciliation run duration must be positive");
        }
        this.demandRepository = demandRepository;
        this.pendingDemandAllocationUsecase = pendingDemandAllocationUsecase;
        this.appClock = appClock;
        this.maxAttemptsPerRun = maxAttemptsPerRun;
        this.maxRunDuration = Duration.ofMillis(maxRunDurationMs);
    }

    /**
     * 找出目前有可配庫存的 pending-demand queue keys，並在單輪 attempt 與時間預算內逐輪處理。
     *
     * <p>每次呼叫 {@link PendingDemandAllocationUsecase} 都是獨立 transaction，而且最多 commit
     * 一筆 demand。成功的 queue key 會進入下一個公平輪次，讓同一次 scheduler 執行有機會繼續
     * 處理下一筆；FIFO blocked、缺貨或失敗的 queue key 留待下一次 scheduler tick。
     */
    public void execute() {
        Instant deadline = appClock.instant().plus(maxRunDuration);

        // 掃描數不超過 attempt budget，確保每個查到的 queue key 至少有一次機會。
        List<AllocationDemandQueueKey> allocatableQueueKeys =
                demandRepository.findAllocatablePendingQueueKeys(appClock.today(), maxAttemptsPerRun);
        allocateInFairRounds(allocatableQueueKeys, deadline);
    }

    /**
     * 每輪讓每個 active queue key 最多嘗試一筆 demand，以保持 queues 之間的公平性。
     *
     * <p>一個 queue key 代表「某個貨主在某個倉庫、某個庫位裡的一種商品」。同一 key
     * 下等待配貨的 demands 會共用庫存並按 FIFO 排隊。每輪每個 key 先處理一筆，成功後下一輪
     * 再處理，避免某條熱門 queue 一次用完所有配置機會。
     *
     * <p>{@link AllocationDemandQueueKey} 只用來重新查詢 queue head，不是 demand 或 demand 清單。
     * key 裡的 SKU 也只是喚醒維度；選到 multi-SKU demand 後，仍會檢查它的全部 required SKUs。
     *
     * <p>成功的 queue key 才會進入下一輪；失敗或無可配 demand 的 key 留待下次 scheduler
     * tick。因此熱門 queue 可以繼續推進，但不會在其他 active queues 取得本輪機會前吃掉全部預算。
     */
    private void allocateInFairRounds(List<AllocationDemandQueueKey> initialQueueKeys, Instant deadline) {
        // activeQueueKeys 只包含本輪仍有機會繼續配置的 queues。
        List<AllocationDemandQueueKey> activeQueueKeys = initialQueueKeys;
        int remainingAttemptBudget = maxAttemptsPerRun;

        while (!activeQueueKeys.isEmpty()) {
            if (remainingAttemptBudget == 0) {
                return;
            }

            // 成功配置的 queue key 才能晉級下一輪，確保每輪每條 queue 最多嘗試一次。
            List<AllocationDemandQueueKey> nextRoundQueueKeys = new ArrayList<>(activeQueueKeys.size());

            for (AllocationDemandQueueKey queueKey : activeQueueKeys) {
                if (!appClock.instant().isBefore(deadline)) {
                    return;
                }
                if (remainingAttemptBudget == 0) {
                    return;
                }
                // 不論成功、無貨或失敗，每次嘗試都會消耗全域工作預算。
                remainingAttemptBudget--;

                if (attemptQueueAllocation(queueKey)) {
                    // 本次成功代表同一 queue 可能還有下一筆 demand，留到下一輪再處理。
                    nextRoundQueueKeys.add(queueKey);
                }
            }

            activeQueueKeys = nextRoundQueueKeys;
        }
    }

    private boolean attemptQueueAllocation(AllocationDemandQueueKey queueKey) {
        try {
            AllocatePendingDemandCommand command = new AllocatePendingDemandCommand(
                    queueKey.ownerId(), queueKey.facilityId(), queueKey.locationId(), queueKey.skuCode());
            return pendingDemandAllocationUsecase.execute(command);
        } catch (RuntimeException exception) {
            logQueueFailure(queueKey, exception);
            return false;
        }
    }

    private static void logQueueFailure(AllocationDemandQueueKey queueKey, RuntimeException exception) {
        log.atError()
                .addKeyValue("ownerId", queueKey.ownerId())
                .addKeyValue("facilityId", queueKey.facilityId())
                .addKeyValue("locationId", queueKey.locationId())
                .addKeyValue("sku", queueKey.skuCode())
                .setCause(exception)
                .log("Pending-demand queue allocation failed; deferred until the next run");
    }
}
