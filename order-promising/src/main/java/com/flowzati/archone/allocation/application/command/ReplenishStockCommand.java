package com.flowzati.archone.allocation.application.command;

import java.util.UUID;

/** 補貨。{@code ownerId} 決定要喚醒哪一個貨主的缺貨佇列。 */
public record ReplenishStockCommand(UUID ownerId, String sku, int quantity) {
  public ReplenishStockCommand {
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
  }
}
