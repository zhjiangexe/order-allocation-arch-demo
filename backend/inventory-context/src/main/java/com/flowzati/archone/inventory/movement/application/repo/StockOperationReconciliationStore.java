package com.flowzati.archone.inventory.movement.application.repo;

import java.util.List;
import java.util.UUID;

/** Read-only consistency check for canonical stock operation groups and reserved counters. */
public interface StockOperationReconciliationStore {

    ReconciliationReport inspect(int sampleLimit);

    record ReconciliationReport(
            List<UUID> heterogeneousOperationIds,
            List<UUID> moveLineCoverageMismatchMoveIds,
            List<UUID> reservedCounterMismatchStockQuantIds) {

        public ReconciliationReport {
            heterogeneousOperationIds = List.copyOf(heterogeneousOperationIds);
            moveLineCoverageMismatchMoveIds = List.copyOf(moveLineCoverageMismatchMoveIds);
            reservedCounterMismatchStockQuantIds = List.copyOf(reservedCounterMismatchStockQuantIds);
        }

        public boolean healthy() {
            return heterogeneousOperationIds.isEmpty()
                    && moveLineCoverageMismatchMoveIds.isEmpty()
                    && reservedCounterMismatchStockQuantIds.isEmpty();
        }
    }
}
