package com.flowzati.archone.inventory.reservation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.repo.StockOperationAssignmentBacklogStore;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentCoordinator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Periodic bounded anti-entropy over confirmed operation queues. */
@Service
public class ReconcileStockOperationBacklogUsecase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStockOperationBacklogUsecase.class);

    private final StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore;
    private final StockOperationAssignmentCoordinator coordinator;
    private final BusinessClock appClock;
    private final int maxAttemptsPerRun;
    private final Duration maxRunDuration;

    public ReconcileStockOperationBacklogUsecase(
            StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore,
            StockOperationAssignmentCoordinator coordinator,
            BusinessClock appClock,
            @Value("${archone.allocation.reconciliation-scheduler-max-attempts-per-run:"
                            + "${archone.allocation.reconciliation-scheduler-scope-limit:200}}")
                    int maxAttemptsPerRun,
            @Value("${archone.allocation.reconciliation-scheduler-max-run-duration-ms:45000}") long maxRunDurationMs) {
        if (maxAttemptsPerRun <= 0 || maxRunDurationMs <= 0) {
            throw new IllegalArgumentException(
                    "Stock operation reconciliation requires positive work and time budgets");
        }
        this.stockOperationAssignmentBacklogStore = stockOperationAssignmentBacklogStore;
        this.coordinator = coordinator;
        this.appClock = appClock;
        this.maxAttemptsPerRun = maxAttemptsPerRun;
        this.maxRunDuration = Duration.ofMillis(maxRunDurationMs);
    }

    public void execute() {
        Instant deadline = appClock.instant().plus(maxRunDuration);
        List<AssignmentQueueKey> queueKeys = stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(
                appClock.today(), maxAttemptsPerRun);
        assignInFairRounds(queueKeys, deadline);
    }

    private void assignInFairRounds(List<AssignmentQueueKey> initialQueueKeys, Instant deadline) {
        List<AssignmentQueueKey> activeQueueKeys = initialQueueKeys;
        int remainingAttempts = maxAttemptsPerRun;
        while (!activeQueueKeys.isEmpty() && remainingAttempts > 0) {
            List<AssignmentQueueKey> nextRound = new ArrayList<>(activeQueueKeys.size());
            for (AssignmentQueueKey queueKey : activeQueueKeys) {
                if (remainingAttempts == 0 || !appClock.instant().isBefore(deadline)) {
                    return;
                }
                remainingAttempts--;
                if (tryAssign(queueKey)) {
                    nextRound.add(queueKey);
                }
            }
            activeQueueKeys = nextRound;
        }
    }

    private boolean tryAssign(AssignmentQueueKey queueKey) {
        try {
            return coordinator.tryAssignNext(queueKey).isPresent();
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("ownerId", queueKey.ownerId())
                    .addKeyValue("fromLocationId", queueKey.fromLocationId())
                    .addKeyValue("sku", queueKey.skuCode())
                    .setCause(exception)
                    .log("Pending operation assignment failed; deferred until the next run");
            return false;
        }
    }
}
