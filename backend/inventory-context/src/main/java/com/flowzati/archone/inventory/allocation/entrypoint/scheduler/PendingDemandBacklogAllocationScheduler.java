package com.flowzati.archone.inventory.allocation.entrypoint.scheduler;

import com.flowzati.archone.inventory.allocation.application.usecase.PendingDemandBacklogAllocationUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic allocation and anti-entropy for allocatable pending demand; Kafka remains the low-latency trigger.
 *
 * <p>Multiple application instances may scan the same queue key. Aggregate optimistic versions and the
 * transactional use case protect correctness; a losing instance defers that queue to the next run.
 * This deliberately favors simple recovery over single-leader scheduling. If reconciliation load
 * becomes material, partition queues or add a distributed lock without moving scheduling into the
 * messaging runtime.
 */
@Component
@ConditionalOnProperty(
        name = "archone.allocation.reconciliation-scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PendingDemandBacklogAllocationScheduler {

    private final PendingDemandBacklogAllocationUsecase pendingDemandBacklogAllocationUsecase;

    public PendingDemandBacklogAllocationScheduler(
            PendingDemandBacklogAllocationUsecase pendingDemandBacklogAllocationUsecase) {
        this.pendingDemandBacklogAllocationUsecase = pendingDemandBacklogAllocationUsecase;
    }

    @Scheduled(
            initialDelayString = "${archone.allocation.reconciliation-scheduler-initial-delay-ms:30000}",
            fixedDelayString = "${archone.allocation.reconciliation-scheduler-delay-ms:60000}")
    public void allocatePendingDemandBacklog() {
        pendingDemandBacklogAllocationUsecase.execute();
    }
}
