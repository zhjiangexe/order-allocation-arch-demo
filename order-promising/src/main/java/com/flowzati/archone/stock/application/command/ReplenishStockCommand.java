package com.flowzati.archone.stock.application.command;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 補貨。五個維度合起來決定要加到哪一列——命中既有列就加數量，否則新開一列。
 *
 * <p>{@code inDate} 與 {@code expiryDate} 是**必填**。少了它們就得定義「這批貨算不算既有列
 * 的一部分」的合併規則，而任何一條規則都會在某些情況下合併掉不該合併的貨；讓它們參與識別，
 * 就沒有規則要定義，也就沒有規則會定錯。
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
public record ReplenishStockCommand(
    UUID ownerId,
    /** 上游說的倉。保留它，是因為喚醒的續做事件必須對外說倉——見 locationId。 */
    UUID facilityId,
    /**
     * 這批貨進的**位置**——所有庫存與待配需求的查詢都以它為準。
     *
     * <p>由 entrypoint 從 {@code facilityId} 解析而得（倉 → 該倉的內部位置）。兩者不會漂移：
     * 它們在同一個邊界、由同一次查表產生。
     */
    UUID locationId,
    String sku,
    LocalDate inDate,
    LocalDate expiryDate,
    int quantity
) {
  public ReplenishStockCommand {
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (locationId == null) {
      throw new IllegalArgumentException("Location ID is required");
    }
    if (facilityId == null) {
      throw new IllegalArgumentException("Fulfillment node ID is required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (inDate == null) {
      throw new IllegalArgumentException("In-date is required");
    }
    if (expiryDate == null) {
      throw new IllegalArgumentException("Expiry date is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Replenishment quantity must be positive");
    }
  }
}
