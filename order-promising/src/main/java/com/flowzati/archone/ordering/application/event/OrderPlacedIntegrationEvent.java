package com.flowzati.archone.ordering.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

public final class OrderPlacedIntegrationEvent extends IntegrationEvent {
  private final UUID orderId;
  private final String sku;
  private final int quantity;
  private final Instant placedAt;

  @JsonCreator
  public OrderPlacedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("orderId") UUID orderId,
      @JsonProperty("sku") String sku,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("placedAt") Instant placedAt
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
    if (placedAt == null) {
      throw new IllegalArgumentException("Placed time is required");
    }
    this.orderId = orderId;
    this.sku = sku;
    this.quantity = quantity;
    this.placedAt = placedAt;
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

  public Instant getPlacedAt() {
    return placedAt;
  }
}
