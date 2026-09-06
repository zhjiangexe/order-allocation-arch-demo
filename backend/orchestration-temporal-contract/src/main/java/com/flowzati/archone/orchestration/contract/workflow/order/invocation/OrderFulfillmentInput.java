package com.flowzati.archone.orchestration.contract.workflow.order.invocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 啟動一張訂單履約 Workflow 所需的最小資料。 */
public record OrderFulfillmentInput(UUID orderId, Instant orderReceivedAt) {

    public OrderFulfillmentInput {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(orderReceivedAt, "Order received time is required");
    }
}
