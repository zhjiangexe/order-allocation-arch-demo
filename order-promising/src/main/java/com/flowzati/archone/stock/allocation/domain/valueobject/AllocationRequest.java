package com.flowzati.archone.stock.allocation.domain.valueobject;

import java.time.Instant;

/**
 * 一次配貨決策的外部條件：每個 SKU 目前可承諾多少，以及決策的時刻。
 *
 * <p><b>刻意不帶「這次補的是哪個 SKU」。</b>那是事件的屬性，不是決策的屬性——一張單的可滿足
 * 性取決於它需要的每一個 SKU，而不是取決於哪一個 SKU 剛好被補了貨。
 *
 * <p>留著那個欄位的代價不是多一個欄位，是**它會被用**：挑單政策拿得到它，就寫得出「只檢查
 * 這一個 SKU」的版本，而那正是整籃原子判斷要消除的行為。拿不到就寫不出來。
 *
 * <p>單 SKU 時 {@code availableBySku} 只有一筆，行為與改動前完全相同。
 */
public record AllocationRequest(
    SkuQuantities availableBySku,
    Instant decisionAt
) {

  public AllocationRequest {
    if (availableBySku == null) {
      throw new IllegalArgumentException("Available quantities are required");
    }
    if (decisionAt == null) {
      throw new IllegalArgumentException("Decision time is required");
    }
  }
}
