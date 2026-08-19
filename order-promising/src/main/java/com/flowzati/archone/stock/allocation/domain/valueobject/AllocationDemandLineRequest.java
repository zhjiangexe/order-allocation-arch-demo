package com.flowzati.archone.stock.allocation.domain.valueobject;

/** 來源 adapter 提供、尚未取得 allocation-owned identity 的 canonicalizable line。 */
public record AllocationDemandLineRequest(
    String sourceLineId,
    String skuCode,
    int quantity
) {

  public AllocationDemandLineRequest {
    if (sourceLineId == null || sourceLineId.isBlank()) {
      throw new IllegalArgumentException("Source line ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Allocation demand line quantity must be positive");
    }
  }
}
