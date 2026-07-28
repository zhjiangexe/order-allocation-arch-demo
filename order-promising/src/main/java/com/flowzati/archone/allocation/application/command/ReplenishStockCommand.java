package com.flowzati.archone.allocation.application.command;

import java.util.UUID;

/**
 * 補貨。{@code ownerId} 決定要喚醒哪一個貨主的缺貨佇列。
 *
 * <p><b>一則命令恰好對應一個貨主的一個 SKU，這是決定而非未完成的擴充。</b>三個理由：
 * partition key 只有在單 SKU 時有唯一正確解（多 SKU 時熱點 SKU 的序列化保證失效）；
 * 一則事件 = 一個 aggregate 的一次狀態變更，多 SKU 等於一次交易鎖多個 {@code StockPool}；
 * inbox 以 eventId 全有全無地去重，多筆時「部分 SKU 失敗」沒有正確的語意可選。
 *
 * <p>上游若以「一張進貨單多 SKU」為單位發事件，<b>拆分點在 entrypoint 不在 usecase</b>
 * ——批次是傳輸層的事，不是領域交易的事。理由與代價見
 * {@code docs/dom-promising-scope.md} 的「補貨的三個決定」。
 */
public record ReplenishStockCommand(UUID ownerId, String sku, int quantity) {
  public ReplenishStockCommand {
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
  }
}
