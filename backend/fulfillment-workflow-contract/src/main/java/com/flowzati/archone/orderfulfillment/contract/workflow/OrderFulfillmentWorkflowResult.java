package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.UUID;

/** Workflow 結束後不可再變動的履約結果。 */
public record OrderFulfillmentWorkflowResult(
        UUID orderId,
        OrderFulfillmentWorkflowOutcome outcome,
        UUID allocationId,
        UUID shipmentId,
        Instant completedAt,
        String detail) {}
