package com.flowzati.archone.inventory.allocation.domain.repository;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import java.util.Optional;
import java.util.UUID;

public interface AllocationCancellationOperationRepository {

    AllocationCancellationOperation save(AllocationCancellationOperation operation);

    Optional<AllocationCancellationOperation> find(UUID allocationDemandId, UUID operationId);
}
