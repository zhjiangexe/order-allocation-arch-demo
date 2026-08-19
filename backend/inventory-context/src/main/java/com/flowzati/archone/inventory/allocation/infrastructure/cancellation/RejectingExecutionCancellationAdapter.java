package com.flowzati.archone.inventory.allocation.infrastructure.cancellation;

import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.ExternalCancellationDecision;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Safe capability gate until a production non-order source supplies its own coordinator. */
@Component
public class RejectingExecutionCancellationAdapter implements AllocationExecutionCancellationCoordinator {

    @Override
    public ExternalCancellationDecision cancelExecution(AllocationDemand demand, UUID cancellationOperationId) {
        return ExternalCancellationDecision.REJECTED;
    }
}
