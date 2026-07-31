package com.flowzati.archone.allocation.domain.service.selector.context;

import com.flowzati.archone.allocation.domain.service.SkuQuantities;
import com.flowzati.archone.allocation.domain.service.selector.AllocationContext;

/**
 * 挑單政策看得到的東西：每個 SKU 還剩多少可承諾。
 *
 * <p><b>沒有「那個 SKU」。</b>整籃原子判斷之下，一張單配不配得下取決於它需要的每一個 SKU；
 * 政策拿不到單一的 SKU 代碼，也就寫不出逐 SKU 獨立判斷的版本。
 */
public record BasicAllocationContext(SkuQuantities availableBySku)
    implements AllocationContext {

  public BasicAllocationContext {
    if (availableBySku == null) {
      throw new IllegalArgumentException("Available quantities are required");
    }
  }
}
