package com.flowzati.archone.inventory.balance.application.result;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 出庫過帳結果；重送時保留呼叫端提供的 correlation，但不再次扣帳或發布完成事件。 */
public record CompleteOutboundMovementsResult(
        UUID allocationId, UUID orderId, UUID shipmentId, Status status, Instant completedAt) {

    public CompleteOutboundMovementsResult {
        Objects.requireNonNull(allocationId, "Allocation ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(status, "Completion status is required");
        Objects.requireNonNull(completedAt, "Completion time is required");
    }

    public enum Status {
        COMPLETED,
        ALREADY_COMPLETED
    }
}
