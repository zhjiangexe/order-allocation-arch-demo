package com.flowzati.archone.allocation.domain.model;

public class StockPool {
  private final Long id;
  private final String sku;
  private int onHandQuantity;
  private int reservedQuantity;
  private final Long version;

  public StockPool(
      Long id,
      String sku,
      int onHandQuantity,
      int reservedQuantity,
      Long version
  ) {
    validateQuantities(onHandQuantity, reservedQuantity);
    this.id = id;
    this.sku = sku;
    this.onHandQuantity = onHandQuantity;
    this.reservedQuantity = reservedQuantity;
    this.version = version;
  }

  /**
   * Compatibility bridge for the legacy persistence adapter. Remove in SR-09 after the
   * adapter persists both on-hand and reserved quantities.
   */
  @Deprecated(forRemoval = true)
  public StockPool(Long id, String sku, Integer available, Long version) {
    this(id, sku, requireLegacyAvailable(available), 0, version);
  }

  public int availableToPromise() {
    return onHandQuantity - reservedQuantity;
  }

  public boolean tryReserve(int quantity) {
    requirePositive(quantity, "Quantity to reserve must be positive");
    if (availableToPromise() >= quantity) {
      reservedQuantity += quantity;
      return true;
    }
    return false;
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

  public Long getId() {
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

  /**
   * Compatibility bridge for the legacy persistence adapter. Remove in SR-09.
   */
  @Deprecated(forRemoval = true)
  public Integer getAvailable() {
    return availableToPromise();
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

  private static int requireLegacyAvailable(Integer available) {
    if (available == null) {
      throw new IllegalArgumentException("Available quantity cannot be null");
    }
    return available;
  }

  private static void requirePositive(int quantity, String message) {
    if (quantity <= 0) {
      throw new IllegalArgumentException(message);
    }
  }
}
