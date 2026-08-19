package com.flowzati.archone.stock.allocation.domain.repository;

import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.stock.allocation.domain.valueobject.WaitingAllocationScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Allocation demand aggregate persistence；不提供 source aggregate 的任何讀寫能力。 */
public interface AllocationDemandRepository {

  AllocationDemand save(AllocationDemand demand);

  Optional<AllocationDemand> findById(UUID allocationDemandId);

  Optional<AllocationDemand> findBySource(SourceAllocationUnit source);

  /** Bounded candidates plus all earlier shared-SKU predecessors needed for strict FIFO. */
  AllocationCandidateBatch findPendingCandidates(
      WaitingAllocationScope scope, String triggeringSku, int candidateLimit);

  /** Oldest pending demand scopes that currently have allocatable stock. */
  List<WaitingAllocationScope> findAllocatablePendingScopes(LocalDate today, int limit);

  /** Pending demands isolated because their execution references violate acceptance invariants. */
  List<UUID> findPendingExecutionAnomalyIds(int limit);
}
