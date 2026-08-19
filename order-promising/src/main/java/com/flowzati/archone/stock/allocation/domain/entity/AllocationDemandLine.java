package com.flowzati.archone.stock.allocation.domain.entity;

import java.util.UUID;

/** Allocation-owned demand line；source line id 只供追蹤，不作 core join key。 */
public record AllocationDemandLine(
    UUID id,
    UUID allocationDemandId,
    String sourceLineId,
    String skuCode,
    int quantity,
    int lineSequence
) {

  public AllocationDemandLine {
    if (id == null || allocationDemandId == null) {
      throw new IllegalArgumentException("Allocation demand line requires its own and demand IDs");
    }
    if (sourceLineId == null || sourceLineId.isBlank()) {
      throw new IllegalArgumentException("Source line ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Allocation demand line quantity must be positive");
    }
    if (lineSequence <= 0) {
      throw new IllegalArgumentException("Allocation demand line sequence must be positive");
    }
  }
}
