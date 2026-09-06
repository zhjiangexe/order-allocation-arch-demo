package com.flowzati.archone.orderfulfillment.application;

import java.time.Instant;
import java.util.UUID;

/** 跨重試保持不變的取消命令內容。 */
public record FulfillmentCancellationRequest(UUID requestId, UUID orderId, Instant requestedAt, String reason) {

    public FulfillmentCancellationRequest {
        if (requestId == null || orderId == null || requestedAt == null) {
            throw new IllegalArgumentException("Cancellation request ID, Order ID and requested time are required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        if (reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must not exceed 512 characters");
        }
    }
}
