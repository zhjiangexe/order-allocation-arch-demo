package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.AllocationDemand;
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
