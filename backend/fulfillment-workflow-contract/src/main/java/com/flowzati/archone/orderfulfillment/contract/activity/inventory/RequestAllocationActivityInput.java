package com.flowzati.archone.orderfulfillment.contract.activity.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 要求 Inventory 開始或冪等重送訂單配貨。 */
public record RequestAllocationActivityInput(String processId, UUID orderId, Instant orderReceivedAt) {

    public RequestAllocationActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(orderReceivedAt, "Order received time is required");
    }
}
