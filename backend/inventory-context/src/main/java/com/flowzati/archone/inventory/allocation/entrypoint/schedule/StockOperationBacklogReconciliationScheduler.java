package com.flowzati.archone.inventory.allocation.entrypoint.schedule;

import com.flowzati.archone.inventory.allocation.application.usecase.StockOperationBacklogReconciler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic anti-entropy for confirmed stock operations; stock events remain the low-latency trigger.
 *
 * <p>Multiple application instances may scan the same queue key. Operation/move optimistic versions and
 * the atomic assignment use case protect correctness; a losing instance defers that queue to the next
 * run. If reconciliation load becomes material, queues can be partitioned without changing the shared
 * selection/planning/assignment pipeline.
 */
@Component
@ConditionalOnProperty(
        name = "archone.allocation.reconciliation-scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StockOperationBacklogReconciliationScheduler {

    private final StockOperationBacklogReconciler stockOperationBacklogUsecase;

    public StockOperationBacklogReconciliationScheduler(StockOperationBacklogReconciler stockOperationBacklogUsecase) {
        this.stockOperationBacklogUsecase = stockOperationBacklogUsecase;
    }

    @Scheduled(
            initialDelayString = "${archone.allocation.reconciliation-scheduler-initial-delay-ms:30000}",
            fixedDelayString = "${archone.allocation.reconciliation-scheduler-delay-ms:900000}")
    public void reconcileAssignmentBacklog() {
        stockOperationBacklogUsecase.execute();
    }
}
