package com.flowzati.archone.catalog.domain.aggregate;

import java.util.UUID;

/**
 * 款之下的具體規格，例如「500ml」「XL」。這就是訂單行上那個 {@code skuCode} 所指的東西。
 *
 * <p>以代理鍵識別，而 {@code (ownerId, skuCode)} 是它的自然鍵：規格編碼由貨主自訂，兩個
 * 貨主都可以有「SKU-A」，而那是兩批不同的貨、不可互換。這件事由資料庫的 unique constraint
 * 保證；訂單行的外鍵也刻意走這組自然鍵，因此每一次參照都必須帶上貨主。
 *
 * <p>刻意不持有溫層：溫層屬款層級（見 {@link Product}）。重量則屬這一層，因為同款的
 * 500ml 與 1L 重量不同，而重量是 R6 成本函數的運費基準。
 */
public class Sku {

  private final UUID id;
  private final UUID ownerId;
  private final String skuCode;
  private final String productCode;
  private final String specName;
  private final int weightGram;

  public Sku(
      UUID id,
      UUID ownerId,
      String skuCode,
      String productCode,
      String specName,
      int weightGram
  ) {
    if (id == null) {
      throw new IllegalArgumentException("SKU ID is required");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (productCode == null || productCode.isBlank()) {
      throw new IllegalArgumentException("Product code is required");
    }
    if (specName == null || specName.isBlank()) {
      throw new IllegalArgumentException("Specification name is required");
    }
    if (weightGram <= 0) {
      throw new IllegalArgumentException("Weight in grams must be positive");
    }
    this.id = id;
    this.ownerId = ownerId;
    this.skuCode = skuCode;
    this.productCode = productCode;
    this.specName = specName;
    this.weightGram = weightGram;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getSpecName() {
    return specName;
  }

  public int getWeightGram() {
    return weightGram;
  }
}
