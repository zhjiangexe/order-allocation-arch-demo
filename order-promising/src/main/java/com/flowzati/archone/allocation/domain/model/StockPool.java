package com.flowzati.archone.allocation.domain.model;

import java.util.UUID;

public class StockPool {
  private final UUID id;
  private final String sku;
  private int onHandQuantity;
  private int reservedQuantity;
  private final Long version;

  public StockPool(
      UUID id,
      String sku,
      int onHandQuantity,
      int reservedQuantity,
      Long version
  ) {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    validateQuantities(onHandQuantity, reservedQuantity);
    this.id = id;
    this.sku = sku;
    this.onHandQuantity = onHandQuantity;
    this.reservedQuantity = reservedQuantity;
    this.version = version;
  }

  public int availableToPromise() {
    return onHandQuantity - reservedQuantity;
  }

  public boolean canReserve(int quantity) {
    requirePositive(quantity, "Quantity to reserve must be positive");
    return availableToPromise() >= quantity;
  }

  public void reserve(int quantity) {
    if (!canReserve(quantity)) {
      throw new IllegalStateException("Insufficient ATP");
    }
    reservedQuantity += quantity;
  }

  public void release(int quantity) {
    requirePositive(quantity, "Quantity to release must be positive");
    if (quantity > reservedQuantity) {
      throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
    }
    reservedQuantity -= quantity;
  }

  public void replenish(int quantity) {
    requirePositive(quantity, "Quantity to replenish must be positive");
    try {
      onHandQuantity = Math.addExact(onHandQuantity, quantity);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("On-hand quantity exceeds supported range", exception);
    }
  }

  public UUID getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public Long getVersion() {
    return version;
  }

  public int getOnHandQuantity() {
    return onHandQuantity;
  }

  public int getReservedQuantity() {
    return reservedQuantity;
  }

  private static void validateQuantities(int onHandQuantity, int reservedQuantity) {
    if (onHandQuantity < 0) {
      throw new IllegalArgumentException("On-hand quantity cannot be negative");
    }
    if (reservedQuantity < 0) {
      throw new IllegalArgumentException("Reserved quantity cannot be negative");
    }
    if (reservedQuantity > onHandQuantity) {
      throw new IllegalArgumentException("Reserved quantity cannot exceed on-hand quantity");
    }
  }

  private static void requirePositive(int quantity, String message) {
    if (quantity <= 0) {
      throw new IllegalArgumentException(message);
    }
  }
}
