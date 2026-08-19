package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.UUID;

/** 供 API 與維運工具查詢的 Workflow 目前狀態。 */
public record OrderFulfillmentWorkflowState(
        UUID orderId,
        OrderFulfillmentWorkflowPhase phase,
        OrderFulfillmentWorkflowAllocationState allocationState,
        OrderFulfillmentWorkflowCancellationState cancellationState,
        UUID cancellationRequestId,
        Instant cancellationRequestedAt,
        OrderFulfillmentWorkflowOutcome outcome,
        UUID allocationId,
        UUID shipmentId,
        Instant cancelledAt,
        Instant updatedAt,
        String detail) {}
