package com.flowzati.archone.inventory.allocation.domain.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Allocation demand aggregate persistence；不提供 source aggregate 的任何讀寫能力。 */
public interface AllocationDemandRepository {

    AllocationDemand save(AllocationDemand demand);

    Optional<AllocationDemand> findById(UUID allocationDemandId);

    Optional<AllocationDemand> findBySource(SourceAllocationUnit source);

    /** 依 FIFO precedence 取最早的 pending demands，供操作台解釋等待原因。 */
    List<AllocationDemand> findPending(int limit);

    /** 取得一路排到指定 demand 為止的完整 FIFO predecessor context。 */
    List<AllocationDemand> findPendingThrough(Instant enqueuedAt, UUID allocationDemandId);

    /** Bounded candidates plus all earlier shared-SKU predecessors needed for strict FIFO. */
    AllocationCandidateBatch findPendingCandidates(AllocationDemandQueueKey queueKey, int candidateLimit);

    /** Oldest pending-demand queue keys that currently have allocatable stock. */
    List<AllocationDemandQueueKey> findAllocatablePendingQueueKeys(LocalDate today, int limit);

    /** Pending demands isolated because their execution references violate acceptance invariants. */
    List<UUID> findPendingExecutionAnomalyIds(int limit);
}
