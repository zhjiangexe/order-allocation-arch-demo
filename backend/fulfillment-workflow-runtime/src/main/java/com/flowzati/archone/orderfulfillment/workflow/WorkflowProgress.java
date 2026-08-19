package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowOutcome;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase;
import java.time.Instant;

/** Workflow Query 顯示所需的目前階段與最後更新資訊。 */
record WorkflowProgress(
        OrderFulfillmentWorkflowPhase phase,
        OrderFulfillmentWorkflowOutcome outcome,
        Instant updatedAt,
        String detail) {}
