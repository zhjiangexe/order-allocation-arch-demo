package com.flowzati.archone.inventory.movement.application.result;

import java.util.List;
import java.util.UUID;

/** Read-only evidence of drift in canonical Stock Operation and reservation invariants. */
public record StockOperationReconciliationReport(
        List<UUID> heterogeneousOperationIds,
        List<UUID> moveLineCoverageMismatchMoveIds,
        List<UUID> reservedCounterMismatchStockQuantIds) {

    public StockOperationReconciliationReport {
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
