package com.flowzati.archone.allocation.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;

import java.util.UUID;

public class StockReplenished extends DomainEvent {
  private final String sku;
  private final int quantity;

  public StockReplenished(UUID id, String sku, int quantity) {
    this.setEventId(id);
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
