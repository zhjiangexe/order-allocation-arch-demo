package com.flowzati.archone.inventory.allocation.application.query;

import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import java.time.LocalDate;
import java.util.List;

/** Discovers pending queue keys that currently have stock worth attempting. */
public interface PendingDemandBacklogQuery {

    List<AllocationDemandQueueKey> findQueueKeysWithAvailableStock(LocalDate today, int limit);
}
