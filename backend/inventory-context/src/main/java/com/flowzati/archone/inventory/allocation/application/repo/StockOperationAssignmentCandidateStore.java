package com.flowzati.archone.inventory.allocation.application.repo;

import com.flowzati.archone.inventory.allocation.application.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.StockOperationAssignmentCandidate;
import java.util.Optional;
import java.util.UUID;

/** Loads one immutable confirmed stock-operation demand and its exact strict-FIFO predecessor, if any. */
public interface StockOperationAssignmentCandidateStore {

    StockOperationAssignmentCandidate findByOperationId(UUID stockOperationId);

    Optional<StockOperationAssignmentCandidate> findNext(AssignmentQueueKey queueKey);
}
