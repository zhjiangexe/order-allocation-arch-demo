package com.flowzati.archone.demo.orderfulfillment.result;

import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import java.util.Objects;

public record WorkflowQueryResult(WorkflowQueryStatus status, OrderFulfillmentSnapshot snapshot) {
    public WorkflowQueryResult {
        Objects.requireNonNull(status, "Workflow query status is required");
        if ((status == WorkflowQueryStatus.AVAILABLE) != (snapshot != null)) {
            throw new IllegalArgumentException("Only an available Workflow query has a snapshot");
        }
    }
}
