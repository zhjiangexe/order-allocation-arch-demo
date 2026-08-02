package com.flowzati.archone.stock.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一張單還欠的東西——配貨的決策單位。
 *
 * <p>來自 {@code demand_lines} view，唯讀、無行為、無生命週期。
 *
 * <p><b>名字裡刻意不帶 {@code Order}。</b>它與 ordering 的 {@code Order} 描述同一張現實中的
 * 單，但那是兩個 context 的兩個模型：後者有十幾個欄位、一組狀態機與 domain events 且可變。
 * 名字帶 {@code Order} 會讓人以為它就是那個 aggregate，然後開始問「為什麼它沒有 status」——
 * 而 allocation 不該認識那個東西正是這個模型存在的理由。持有 {@code orderId} 是為了建預留與
 * 發事件，不代表它是訂單。
 *
 * <p><b>刻意不帶狀態。</b>ordering 的配貨狀態落後於 allocation 的決策（它由事件推進），拿它
 * 當閘門會讓同一筆需求被預留兩次。
 *
 * <p><b>沒有時間戳。</b>佇列的順序由到達順序決定（主鍵是 UUID v7，時間編在裡面），而
 * 「這張單等多久了」現在由搬運的 {@code createdAt} 回答——那是執行層自己的資料，不必從
 * 需求這一側複製一份過來。
 *
 * <p><b>{@code lines} 是這張單「還欠」的全部行，不是命中某個 SKU 的那些。</b>一張單整批配到
 * 或整批不配（ship-complete），所以決策需要看見它的每一條行；只給命中該 SKU 的行，就無從判斷
 * 「整籃是否同時可滿足」。這一點在收單仍限制單行時就成立，R8 放寬時查詢與型別都不必重寫。
 */
public record Demand(
    UUID orderId,
    UUID ownerId,
    UUID locationId,
    List<DemandLine> lines
) {

  public Demand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (locationId == null) {
      throw new IllegalArgumentException("Fulfillment node ID is required");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Demand must contain at least one line");
    }
    lines = List.copyOf(lines);
  }

  /**
   * 這張單總共還欠什麼——SKU 對數量的映射，同一個 SKU 的多行已經加總。
   *
   * <p>給的是聚合後的需求而不是行的集合，理由與 ordering 的 {@code Order.getDemand()} 相同：
   * 配貨端因此沒有「行」可以逐個處理，「逐行判斷可滿足性、配得到就預留」這種違反 ship-complete
   * 的寫法表達不出來。
   */
  public Map<String, Integer> totalsBySku() {
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (DemandLine line : lines) {
      totals.merge(line.skuCode(), line.quantity(), Integer::sum);
    }
    return Map.copyOf(totals);
  }

  /**
   * 這張單對某一個 SKU 還欠幾件。
   *
   * <p>不存在時明確拋錯，而不是回 0：把「這張單根本不要這個 SKU」當成「要 0 個」，會讓配貨端
   * 安靜地把它當作已滿足，而那正是跨 SKU 訂單被整張配掉的路徑。
   */
  public int demandFor(String skuCode) {
    Integer quantity = totalsBySku().get(skuCode);
    if (quantity == null) {
      throw new IllegalArgumentException("Demand has no line for SKU " + skuCode);
    }
    return quantity;
  }
}
