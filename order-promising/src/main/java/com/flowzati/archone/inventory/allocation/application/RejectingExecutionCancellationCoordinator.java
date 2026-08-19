package com.flowzati.archone.inventory.allocation.application;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Safe capability gate until a production non-order source supplies its own coordinator. */
@Component
public class RejectingExecutionCancellationCoordinator
    implements AllocationExecutionCancellationCoordinator {

  @Override
  public ExternalCancellationDecision cancelExecution(
      AllocationDemand demand, UUID cancellationOperationId) {
    return ExternalCancellationDecision.REJECTED;
  }
}
