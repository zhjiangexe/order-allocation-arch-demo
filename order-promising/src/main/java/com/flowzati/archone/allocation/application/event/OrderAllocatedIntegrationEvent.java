package com.flowzati.archone.allocation.application.event;

import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

public final class OrderAllocatedIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final UUID reservationId;
  private final String sku;
  private final int quantity;
  private final Instant allocatedAt;

  public OrderAllocatedIntegrationEvent(
      UUID eventId,
      UUID orderId,
      UUID reservationId,
      String sku,
      int quantity,
      Instant allocatedAt
  ) {
    super(eventId);
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (reservationId == null) {
      throw new IllegalArgumentException("Reservation ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Order quantity must be positive");
    }
    if (allocatedAt == null) {
      throw new IllegalArgumentException("Allocated time is required");
    }
    this.orderId = orderId;
    this.reservationId = reservationId;
    this.sku = sku;
    this.quantity = quantity;
    this.allocatedAt = allocatedAt;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getReservationId() {
    return reservationId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }
}
