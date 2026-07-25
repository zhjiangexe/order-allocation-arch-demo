package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.model.Order;
import java.time.Instant;
import java.util.UUID;

/**
 * k6 polls this to measure "time to allocation decision": the exact
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
