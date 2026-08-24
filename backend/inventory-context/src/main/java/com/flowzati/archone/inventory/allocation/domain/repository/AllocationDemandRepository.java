package com.flowzati.archone.inventory.allocation.domain.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.util.Optional;
import java.util.UUID;

/** Allocation demand aggregate persistence；不提供 source aggregate 的任何讀寫能力。 */
public interface AllocationDemandRepository {

    AllocationDemand save(AllocationDemand demand);

    Optional<AllocationDemand> findById(UUID allocationDemandId);

    Optional<AllocationDemand> findBySource(SourceAllocationUnit source);
}
