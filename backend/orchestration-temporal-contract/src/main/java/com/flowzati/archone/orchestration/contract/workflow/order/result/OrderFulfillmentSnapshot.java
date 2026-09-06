package com.flowzati.archone.orchestration.contract.workflow.order.result;

import java.time.Instant;
import java.util.UUID;

/**
 * 供 API 與維運工具查詢的 Workflow 目前狀態。
 *
 * @param updatedAt 目前 phase 的進入時間；不是任意 checkpoint 的最後更新時間
 */
public record OrderFulfillmentSnapshot(
        UUID orderId,
        OrderFulfillmentPhase phase,
        OrderFulfillmentAllocationState allocationState,
        OrderFulfillmentCancellationState cancellationState,
        UUID cancellationRequestId,
        Instant cancellationRequestedAt,
        OrderFulfillmentOutcome outcome,
        UUID stockOperationId,
        UUID shipmentId,
        ShipmentTerminalStatus shipmentTerminalStatus,
        Instant shipmentTerminalAt,
        Instant cancelledAt,
        Instant updatedAt) {}
