package com.flowzati.archone.ordering.application.invocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 將一筆具穩定身分的取消請求提交給 Ordering。 */
public record CancelOrderCommand(UUID requestId, UUID orderId, Instant cancelledAt, String reason) {

    public CancelOrderCommand {
        Objects.requireNonNull(requestId, "Cancellation request ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(cancelledAt, "Cancellation completion time is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        if (reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must not exceed 512 characters");
        }
    }
}
