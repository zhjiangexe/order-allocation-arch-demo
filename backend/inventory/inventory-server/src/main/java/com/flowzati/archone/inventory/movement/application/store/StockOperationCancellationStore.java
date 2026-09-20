package com.flowzati.archone.inventory.movement.application.store;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import java.util.Optional;
import java.util.UUID;

/** Application-owned data-access boundary for idempotent cancellation workflow state. */
public interface StockOperationCancellationStore {

    Optional<StockOperationCancellation> find(UUID stockOperationId, UUID cancellationOperationId);

    StockOperationCancellation save(StockOperationCancellation operation);
}
