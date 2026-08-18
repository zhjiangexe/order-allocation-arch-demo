package com.flowzati.archone.stock.domain.repository;

import com.flowzati.archone.stock.domain.model.AllocationCancellationOperation;
import java.util.Optional;
import java.util.UUID;

public interface AllocationCancellationOperationRepository {

  AllocationCancellationOperation save(AllocationCancellationOperation operation);

  Optional<AllocationCancellationOperation> find(UUID allocationDemandId, UUID operationId);
}
