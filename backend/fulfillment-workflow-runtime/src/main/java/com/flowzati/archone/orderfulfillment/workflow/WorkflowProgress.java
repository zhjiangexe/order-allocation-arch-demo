package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus;
import java.time.Instant;

/** Workflow Query 顯示所需的目前階段與最後更新資訊。 */
record WorkflowProgress(
        OrderFulfillmentWorkflowPhase phase, OrderFulfillmentWorkflowStatus outcome, Instant updatedAt, String detail) {

    WorkflowProgress enter(OrderFulfillmentWorkflowPhase nextPhase, Instant occurredAt, String nextDetail) {
        return new WorkflowProgress(nextPhase, null, occurredAt, nextDetail);
    }

    WorkflowProgress update(Instant occurredAt, String nextDetail) {
        return new WorkflowProgress(phase, outcome, occurredAt, nextDetail);
    }
}
