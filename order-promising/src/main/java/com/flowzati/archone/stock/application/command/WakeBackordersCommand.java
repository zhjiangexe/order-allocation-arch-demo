package com.flowzati.archone.stock.application.command;

import java.util.UUID;

/**
 * 續做喚醒：把上一輪因為批次上限而沒喚醒完的缺貨佇列接著處理。
 *
 * <p><b>不帶數量</b>——它不是補貨。庫存在上一輪就已經加進去了，這則命令要做的只是繼續把佇列
 * 餵完。共用 {@code ReplenishStockCommand} 並塞一個 0 的話，那個 0 會需要在每一個讀到數量的
 * 地方被特判，而「數量必須為正」這條檢查也就守不住了。
 */
public record WakeBackordersCommand(UUID ownerId, UUID facilityId, UUID locationId, String sku) {
  public WakeBackordersCommand {
    if (ownerId == null || facilityId == null || locationId == null) {
      throw new IllegalArgumentException("Owner ID and node ID are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
  }
}
