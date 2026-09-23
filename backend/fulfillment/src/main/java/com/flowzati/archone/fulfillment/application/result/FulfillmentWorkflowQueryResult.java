package com.flowzati.archone.fulfillment.application.result;

import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import java.util.Objects;

public record FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus status, OrderFulfillmentSnapshot snapshot) {
    public FulfillmentWorkflowQueryResult {
        Objects.requireNonNull(status, "Workflow query status is required");
        if ((status == FulfillmentWorkflowQueryStatus.AVAILABLE) != (snapshot != null)) {
            throw new IllegalArgumentException("Only an available Workflow query has a snapshot");
        }
    }
}
