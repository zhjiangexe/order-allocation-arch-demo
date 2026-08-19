package com.flowzati.archone.stock.inventory.application.command;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 在本地 stock context 確認的一段式收貨。貨主、設施、庫位、SKU、收貨日、效期與
 * 實收數量足以建立並完成一張 inbound picking。
 *
 * <p>{@code inDate} 與 {@code expiryDate} 是**必填**。少了它們就得定義「這批貨算不算既有列
 * 的一部分」的合併規則，而任何一條規則都會在某些情況下合併掉不該合併的貨；讓它們參與識別，
 * 就沒有規則要定義，也就沒有規則會定錯。
 *
 * <p>目前是一段式簡化流程：確認時同交易建立 picking、move、move line 並完成，不表達預約到貨、
 * 卸貨、驗收或上架等待。未來拆成多階段時，這個 command 應改為完成既有 receipt，而不是繼續
 * 擴張成一張多行收貨單。
 */
public record ConfirmStockReceiptCommand(
    UUID ownerId,
    UUID facilityId,
    UUID locationId,
    String sku,
    LocalDate inDate,
    LocalDate expiryDate,
    int quantity
) {
  public ConfirmStockReceiptCommand {
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (facilityId == null) {
      throw new IllegalArgumentException("Facility ID is required");
    }
    if (locationId == null) {
      throw new IllegalArgumentException("Location ID is required");
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
      throw new IllegalArgumentException("Received quantity must be positive");
    }
  }
}
