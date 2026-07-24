package com.flowzati.archone.allocation.application.command;

public record ReplenishStockCommand(String sku, int quantity) {
  public ReplenishStockCommand {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
  }
}
