package com.flowzati.archone.allocation.application.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.common.integration.IntegrationEvent;
import java.util.UUID;

public final class StockReplenishedIntegrationEvent extends IntegrationEvent {
  private final String sku;
  private final int quantity;

  @JsonCreator
  public StockReplenishedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("sku") String sku,
      @JsonProperty("quantity") int quantity
  ) {
    super(eventId);
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
    this.sku = sku;
    this.quantity = quantity;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }
}
