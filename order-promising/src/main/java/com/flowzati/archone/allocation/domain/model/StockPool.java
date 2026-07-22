package com.flowzati.archone.allocation.domain.model;

public class StockPool {
  private final Long id;
  private final String sku;
  private Integer available;
  private final Long version;

  public StockPool(Long id, String sku, Integer available, Long version) {
    this.id = id;
    this.sku = sku;
    this.available = available;
    this.version = version;
  }

  public boolean tryAllocate(int quantity) {
    if (available >= quantity) {
      this.available -= quantity;
      return true;
    }
    return false;
  }

  public void replenish(int quantity) {
    if (quantity < 0) {
      throw new IllegalArgumentException("Quantity to replenish cannot be negative");
    }
    this.available += quantity;
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

  public Integer getAvailable() {
    return available;
  }
}
