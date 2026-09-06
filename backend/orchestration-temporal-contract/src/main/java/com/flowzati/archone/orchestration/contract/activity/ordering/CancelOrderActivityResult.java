package com.flowzati.archone.orchestration.contract.activity.ordering;

import java.util.Objects;
import java.util.UUID;

/** Ordering 取消 transaction 的 typed 業務結果。 */
public record CancelOrderActivityResult(UUID orderId, CancelOrderActivityStatus status) {

    public CancelOrderActivityResult {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(status, "Cancel order status is required");
    }
}
