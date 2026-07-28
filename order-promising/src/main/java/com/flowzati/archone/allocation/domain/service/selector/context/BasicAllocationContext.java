package com.flowzati.archone.allocation.domain.service.selector.context;

import com.flowzati.archone.allocation.domain.service.selector.AllocationContext;

/**
 * 一次配貨決策的輸入：決策對象是「某一個 SKU 的庫存池」，因此 {@code skuCode} 與可承諾量
 * 一起構成 context。
 *
 * <p>訂單改以行表達需求之後，policy 不能再讀「訂單的數量」——那個東西不存在了，只有「訂單
 * 對某個 SKU 的需求量」。要問這個問題就得知道是哪個 SKU。
 */
public record BasicAllocationContext(String skuCode, int availableToPromise)
    implements AllocationContext {

  public BasicAllocationContext {
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (availableToPromise < 0) {
      throw new IllegalArgumentException("Available to promise cannot be negative");
    }
  }
}
