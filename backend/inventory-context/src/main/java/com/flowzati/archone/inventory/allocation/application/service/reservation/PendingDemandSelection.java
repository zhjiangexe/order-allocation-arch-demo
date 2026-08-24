package com.flowzati.archone.inventory.allocation.application.service.reservation;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import java.util.Optional;

/** Loads the bounded FIFO facts needed to evaluate one pending allocation attempt. */
public interface PendingDemandSelection {

    PendingDemandQueuePosition positionOf(AllocationDemand demand);

    Optional<PendingDemandQueuePosition> findQueueHead(AllocationDemandQueueKey queueKey);
}
