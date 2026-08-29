package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.time.Instant;
import java.util.UUID;

/** 供 API 與維運工具查詢的 Workflow 目前狀態。 */
public record OrderFulfillmentWorkflowSnapshot(
        UUID orderId,
        OrderFulfillmentWorkflowPhase phase,
        OrderFulfillmentWorkflowAllocationState allocationState,
        OrderFulfillmentWorkflowCancellationState cancellationState,
        UUID cancellationRequestId,
        Instant cancellationRequestedAt,
        OrderFulfillmentWorkflowStatus outcome,
        UUID stockOperationId,
        UUID shipmentId,
        ShipmentTerminalStatus shipmentTerminalStatus,
        Instant shipmentTerminalAt,
        Instant cancelledAt,
        Instant updatedAt,
        String detail) {}
