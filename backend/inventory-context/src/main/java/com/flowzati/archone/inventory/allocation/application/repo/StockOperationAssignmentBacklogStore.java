package com.flowzati.archone.inventory.allocation.application.repo;

import com.flowzati.archone.inventory.allocation.application.AssignmentQueueKey;
import java.time.LocalDate;
import java.util.List;

/** Discovers bounded confirmed-operation queues and the enqueue time used for backlog-age reporting. */
public interface StockOperationAssignmentBacklogStore {

    List<AssignmentQueueKey> findQueueKeysWithAvailableStock(LocalDate today, int limit);
}
