package com.flowzati.archone.ordering.entrypoint.rest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 下單命令的 JSON request body。
 *
 * <p>欄位規則由 {@code Order} aggregate 與資料庫的約束驗證，不在此重複——包括「恰好一筆
 * 行」這條收單政策,以及行的 SKU 必須存在於該貨主的主檔。
 */
public record PlaceOrderRequest(
    UUID ownerId,
    String externalOrderNo,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    UUID requestedNodeId,
    List<Line> lines
) {

  public record Line(String skuCode, int quantity) {
  }
}
