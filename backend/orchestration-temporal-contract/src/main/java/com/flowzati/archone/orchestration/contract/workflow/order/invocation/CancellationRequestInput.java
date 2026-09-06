package com.flowzati.archone.orchestration.contract.workflow.order.invocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 人工、逾期政策或上游系統要求停止尚未離倉的履約流程。 */
public record CancellationRequestInput(UUID requestId, UUID orderId, Instant requestedAt, String reason) {

    public CancellationRequestInput {
        Objects.requireNonNull(requestId, "Cancellation request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(requestedAt, "Cancellation request time is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
    }
}
