package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.AllocationDemand;
import java.util.UUID;

/**
 * External port for stopping already-allocated physical execution.
 * Implementations are called without an allocation database transaction or held stock locks.
 */
public interface AllocationExecutionCancellationCoordinator {

  ExternalCancellationDecision cancelExecution(
      AllocationDemand demand, UUID cancellationOperationId);
}
