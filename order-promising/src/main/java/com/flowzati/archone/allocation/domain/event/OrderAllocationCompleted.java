package com.flowzati.archone.allocation.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Order、StockPool 與 StockReservation 已共同完成完整配置的領域事實。
 */
public record OrderAllocationCompleted(
    UUID orderId,
    UUID reservationId,
    String sku,
    int quantity,
    Instant allocatedAt
) implements DomainEvent {

  public OrderAllocationCompleted {
    if (orderId == null || reservationId == null) {
      throw new IllegalArgumentException("Order ID and reservation ID are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Quantity must be positive");
    }
    if (allocatedAt == null) {
      throw new IllegalArgumentException("Allocated time is required");
    }
  }
}
