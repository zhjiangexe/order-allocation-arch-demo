package com.flowzati.archone.ordering.domain.event;

import java.util.List;
import java.util.Set;

/**
 * 事件裡那一行的內容：行號、SKU 與數量。
 *
 * <p>是 {@code OrderLine} 在事件發生當下的投影，不是它本身——刻意不帶 {@code status} 與
 * {@code backorderedSince}，那些是行的狀態，而事件本身已經說明了發生什麼事。
 *
 * <p>三個帶行的事件（下單、缺貨、取消）平等引用這個型別，而不是共用其中一個事件的內部
 * record：那樣會讓取消事件裡出現「下單事件的行」這種說謊的名字，也會讓其中一個事件加欄位
 * 時另外兩個被迫跟著長出不需要的欄位。
 */
public record LineSnapshot(int lineNo, String skuCode, int quantity) {

  /**
   * 事件所涉及的唯一 SKU，語意與 {@code Order.requireSingleSku()} 相同：
   * <strong>此呼叫端踩在「每張單只碰一個 SKU」這個假設上</strong>。
   *
   * <p>事件的下游也有非要一個值不可的地方——outbox 的 partition key，以及尚未改為攜帶行清單
   * 的 integration event 契約。與其讓它們各自從 {@code lines()} 挖，不如統一經過這個名字：
   * R8 放寬多行時，搜尋 {@code requireSingleSku} 就是完整的待修清單。名稱刻意與 {@code Order}
   * 上的那個一字不差，可搜尋性優先於字面上的簡潔。
   *
   * <p>判準是 SKU 的個數而不是行數：同一個 SKU 的兩行摺得出唯一的 key，不該被拒絕。
   */
  public static String requireSingleSku(List<LineSnapshot> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new IllegalStateException("Event carries no lines");
    }
    Set<String> skuCodes = lines.stream().map(LineSnapshot::skuCode)
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    if (skuCodes.size() != 1) {
      throw new IllegalStateException(
          "This caller assumes a single-SKU order, but the event spans " + skuCodes.size()
              + " SKUs");
    }
    return skuCodes.iterator().next();
  }

  /** 事件所涉及的唯一 SKU 的那些行的數量總和。理由同 {@link #requireSingleSku}。 */
  public static int totalQuantity(List<LineSnapshot> lines) {
    return lines.stream().mapToInt(LineSnapshot::quantity).sum();
  }
}
