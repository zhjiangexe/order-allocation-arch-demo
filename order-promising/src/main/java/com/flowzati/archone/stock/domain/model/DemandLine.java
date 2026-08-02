package com.flowzati.archone.stock.domain.model;

import java.util.UUID;

/**
 * 一條待配的訂單行——配貨眼中的一筆需求。
 *
 * <p>來自 {@code demand_lines} view，唯讀。它與 ordering 的 {@code OrderLine} 描述同一條行，
 * 但只帶配貨用得到的三個值：預留要指向哪一行、要哪個 SKU、要幾件。
 *
 * <p><b>不帶貨主與倉別</b>——一張單一個貨主一個倉，那兩個值屬於 {@link Demand}，放在行上是
 * 重複。view 的每一列都會帶（它是行的 view），映射時提到 order 層級。
 *
 * <p><b>不帶狀態。</b>ordering 的配貨狀態由事件推進，落後於 allocation 自己的決策；帶著它
 * 遲早有人拿去當閘門，而那會讓同一筆需求被預留兩次。「還欠什麼」由 view 對
 * {@code stock_reservations} 的 {@code NOT EXISTS} 決定——一條行有了 ACTIVE 或 CONSUMED 的
 * 預留就直接從 view 消失。
 */
public record DemandLine(UUID orderLineId, String skuCode, int quantity) {

  public DemandLine {
    if (orderLineId == null) {
      throw new IllegalArgumentException("Order line ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Demand quantity must be positive");
    }
  }
}
