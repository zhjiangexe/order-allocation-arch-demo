package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import java.time.LocalDate;
import java.util.List;

/** Discovers bounded confirmed-operation queues and the enqueue time used for backlog-age reporting. */
public interface StockOperationAssignmentBacklogStore {

    /** Scans queue identities after an exclusive cursor; null starts a new sweep. FIFO within each queue is unchanged. */
    List<AssignmentQueueKey> findQueueKeysWithAvailableStock(LocalDate today, int limit, AssignmentQueueKey after);
}
