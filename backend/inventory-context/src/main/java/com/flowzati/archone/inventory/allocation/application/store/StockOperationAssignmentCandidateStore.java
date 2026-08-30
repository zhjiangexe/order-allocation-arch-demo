package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.allocation.application.projection.StockOperationAssignmentCandidate;
import com.flowzati.archone.inventory.allocation.application.valueobject.AssignmentQueueKey;
import java.util.Optional;
import java.util.UUID;

/** Loads one immutable confirmed stock-operation demand and its exact strict-FIFO predecessor, if any. */
public interface StockOperationAssignmentCandidateStore {

    StockOperationAssignmentCandidate findByOperationId(UUID stockOperationId);

    Optional<StockOperationAssignmentCandidate> findNext(AssignmentQueueKey queueKey);
}
