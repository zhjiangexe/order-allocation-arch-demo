package com.flowzati.archone.inventory.allocation.application.store;

import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.state.StockOperationPredecessor;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockOperationDemand;
import java.util.Optional;
import java.util.UUID;

/** Reads confirmed demand and precedence using a policy supplied by the application. */
public interface StockOperationAssignmentCandidateStore {
    Optional<StockOperationDemand> findDemand(UUID stockOperationId);

    Optional<StockOperationPredecessor> findPredecessor(StockOperationDemand demand, AllocationSequencePolicy policy);

    /** 只選取隊首的完整需求；前序需求由 Coordinator 另行查詢並判斷。 */
    Optional<StockOperationDemand> findNext(AssignmentQueueKey queueKey, AllocationSequencePolicy policy);
}
