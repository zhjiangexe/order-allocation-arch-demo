package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus;
import java.time.Instant;

/** Workflow Query 顯示所需的目前階段與最後更新資訊。 */
record WorkflowProgress(
        OrderFulfillmentWorkflowPhase phase,
        OrderFulfillmentWorkflowStatus outcome,
        Instant updatedAt,
        String detail) {}
