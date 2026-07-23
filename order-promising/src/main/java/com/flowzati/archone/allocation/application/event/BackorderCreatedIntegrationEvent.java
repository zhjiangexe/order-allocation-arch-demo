package com.flowzati.archone.allocation.application.event;

import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

public final class BackorderCreatedIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final String sku;
  private final int quantity;
  private final Instant backorderedSince;

  public BackorderCreatedIntegrationEvent(
      UUID eventId,
      UUID orderId,
      String sku,
      int quantity,
      Instant backorderedSince
  ) {
    super(eventId);
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Order quantity must be positive");
    }
    if (backorderedSince == null) {
      throw new IllegalArgumentException("Backordered time is required");
    }
    this.orderId = orderId;
    this.sku = sku;
    this.quantity = quantity;
    this.backorderedSince = backorderedSince;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }
}
