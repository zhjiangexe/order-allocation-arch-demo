package com.flowzati.archone.orderfulfillment.contract.activity.ordering;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 要求 Ordering 執行冪等取消 transaction。 */
public record CancelOrderActivityInput(
        String processId, UUID requestId, UUID orderId, Instant requestedAt, String reason) {

    public CancelOrderActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(requestId, "Cancellation request ID is required");
        Objects.requireNonNull(requestedAt, "Cancellation request time is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
    }
}
