package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.model.Order;
import java.time.Instant;
import java.util.UUID;

/**
 * 單一訂單的對外表示，由下單、最近訂單列表與單筆查詢三處共用——客戶端因此只需要一個
 * 訂單模型，而不是「建立時拿到一種、查詢時拿到另一種」。
 *
 * <p>k6 polls this to measure "time to allocation decision": the exact
 * {@code allocatedAt}/{@code backOrderedSince} timestamp, not the polling interval, is
 * what should drive the latency chart — polling only tells k6 *when to stop asking*.
 */
public record OrderStatusResponse(
    UUID orderId,
    String sku,
    int quantity,
    String status,
    Instant placedAt,
    Instant allocatedAt,
    Instant backOrderedSince,
    Instant cancelledAt
) {

  static OrderStatusResponse from(Order order) {
    return new OrderStatusResponse(
        order.getId(),
        order.getSku(),
        order.getQuantity(),
        order.getStatus().name(),
        order.getPlacedAt(),
        order.getAllocatedAt(),
        order.getBackOrderedSince(),
        order.getCancelledAt());
  }
}
