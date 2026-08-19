package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;

/**
 * 來源側一個可獨立競爭庫存的穩定單元。
 *
 * <p>{@code sourceId} 必須已由 adapter canonicalize，並在同一 source type 內全域唯一；
 * allocation core 不保存或猜測外部 namespace。{@code allocationUnitKey} 必須來自穩定的業務
 * 拆分識別，不能使用 retry-specific random value 或可變 location/picking 設定。
 */
public record SourceAllocationUnit(
    AllocationSourceType sourceType,
    String sourceId,
    String allocationUnitKey
) {

  public static final String PRIMARY = "PRIMARY";

  public SourceAllocationUnit {
    if (sourceType == null) {
      throw new IllegalArgumentException("Allocation source type is required");
    }
    requireText(sourceId, "Canonical source ID is required");
    requireText(allocationUnitKey, "Allocation unit key is required");
  }

  public static SourceAllocationUnit primaryOrder(String canonicalOrderId) {
    return new SourceAllocationUnit(AllocationSourceType.ORDER, canonicalOrderId, PRIMARY);
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
