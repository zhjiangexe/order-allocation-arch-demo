package com.flowzati.archone.inventory.movement.application.store;

import com.flowzati.archone.inventory.movement.application.result.StockOperationReconciliationReport;

/** Read-only consistency check for canonical stock operation groups and reserved counters. */
public interface StockOperationReconciliationStore {

    StockOperationReconciliationReport inspect(int sampleLimit);
}
