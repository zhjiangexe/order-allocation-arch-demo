package com.flowzati.archone.inventory.allocation.application.query;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side access used to build allocation-demand views; it never participates in allocation decisions. */
public interface AllocationDemandQueryRepository {

    List<AllocationDemand> findPending(int limit);

    Optional<UUID> findBlockingDemandId(AllocationDemand candidate);
}
