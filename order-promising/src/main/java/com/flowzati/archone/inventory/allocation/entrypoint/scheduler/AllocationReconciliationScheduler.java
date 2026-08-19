package com.flowzati.archone.inventory.allocation.entrypoint.scheduler;

import com.flowzati.archone.inventory.allocation.application.usecase.ReconcileWaitingDemandUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic anti-entropy for allocatable waiting demand; Kafka remains the low-latency trigger.
 *
 * <p>Multiple application instances may scan the same scope. Aggregate optimistic versions and the
 * transactional use case protect correctness; a losing instance defers that scope to the next run.
 * This deliberately favors simple recovery over single-leader scheduling. If reconciliation load
 * becomes material, partition scopes or add a distributed lock without moving scheduling into the
 * messaging runtime.
 */
@Component
@ConditionalOnProperty(
    name = "archone.allocation.reconciliation-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AllocationReconciliationScheduler {

  private final ReconcileWaitingDemandUsecase reconcileWaitingDemandUsecase;

  public AllocationReconciliationScheduler(ReconcileWaitingDemandUsecase reconcileWaitingDemandUsecase) {
    this.reconcileWaitingDemandUsecase = reconcileWaitingDemandUsecase;
  }

  @Scheduled(
      initialDelayString = "${archone.allocation.reconciliation-scheduler-initial-delay-ms:30000}",
      fixedDelayString = "${archone.allocation.reconciliation-scheduler-delay-ms:30000}")
  public void reconcileAllocatableWaitingDemand() {
    reconcileWaitingDemandUsecase.execute();
  }
}
