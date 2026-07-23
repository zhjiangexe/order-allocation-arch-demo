package com.flowzati.archone.allocation.application.event;

import com.flowzati.archone.common.integration.IntegrationEvent;
import java.util.UUID;

public final class StockReplenishedIntegrationEvent extends IntegrationEvent {
  private final String sku;
  private final int quantity;

  public StockReplenishedIntegrationEvent(UUID eventId, String sku, int quantity) {
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
