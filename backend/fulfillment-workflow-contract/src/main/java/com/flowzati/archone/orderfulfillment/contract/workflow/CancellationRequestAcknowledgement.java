package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.util.Objects;
import java.util.UUID;

/** Update 的立即受理結果，不代表 WMS 或 Order cancellation 已完成，也不是 OrderCancelled fact。 */
public record CancellationRequestAcknowledgement(
        CancellationRequestStatus status, UUID effectiveRequestId, String detail) {

    public CancellationRequestAcknowledgement {
        Objects.requireNonNull(status, "Cancellation request status is required");
        Objects.requireNonNull(effectiveRequestId, "Effective cancellation request ID is required");
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("Cancellation request detail is required");
        }
    }
}
