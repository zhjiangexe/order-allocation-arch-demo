package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.store.StockOperationAssignmentBacklogStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Periodic paged sweep over confirmed operation queues. */
@Service
public class StockOperationBacklogReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockOperationBacklogReconciler.class);

    private final StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore;
    private final AssignNextStockOperationUsecase assignNextStockOperationUsecase;
    private final BusinessClock appClock;
    private static final int PAGE_SIZE = 200;

    public StockOperationBacklogReconciler(
            StockOperationAssignmentBacklogStore stockOperationAssignmentBacklogStore,
            AssignNextStockOperationUsecase assignNextStockOperationUsecase,
            BusinessClock appClock) {
        this.stockOperationAssignmentBacklogStore = stockOperationAssignmentBacklogStore;
        this.assignNextStockOperationUsecase = assignNextStockOperationUsecase;
        this.appClock = appClock;
    }

    public void execute() {
        AssignmentQueueKey cursor = null;
        var today = appClock.today();
        while (true) {
            List<AssignmentQueueKey> queueKeys =
                    stockOperationAssignmentBacklogStore.findQueueKeysWithAvailableStock(today, PAGE_SIZE, cursor);
            if (queueKeys.isEmpty()) {
                return;
            }
            assignInFairRounds(queueKeys);
            // Keyset pagination remains valid even when successful queues disappear.
            cursor = queueKeys.getLast();
        }
    }

    private void assignInFairRounds(List<AssignmentQueueKey> initialQueueKeys) {
        List<AssignmentQueueKey> activeQueueKeys = initialQueueKeys;
        while (!activeQueueKeys.isEmpty()) {
            List<AssignmentQueueKey> nextRound = new ArrayList<>(activeQueueKeys.size());
            for (AssignmentQueueKey queueKey : activeQueueKeys) {
                if (tryAssign(queueKey)) {
                    nextRound.add(queueKey);
                }
            }
            activeQueueKeys = nextRound;
        }
    }

    private boolean tryAssign(AssignmentQueueKey queueKey) {
        try {
            return assignNextStockOperationUsecase.execute(queueKey).isPresent();
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
