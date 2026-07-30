package com.flowzati.archone.allocation.domain.model;

public enum ReservationStatus {
  ACTIVE,
  RELEASED,
  /**
   * 出貨時扣掉在手量後套用。
   *
   * <p><b>本階段不產生這個狀態</b>——它為履約層的兩本帳準備。現在就加，是因為下一個
   * change 的待配佇列 view 會用 {@code status IN ('ACTIVE', 'CONSUMED')} 當「已滿足」的
   * 謂詞，而那個 change 緊接在後。
   *
   * <p>日後再擴充這個 enum 時<b>必須同步檢查那個 view</b>：漏掉會讓已出貨的訂單重新出現
   * 在待配佇列，而當下沒有任何測試會發現。
   */
  CONSUMED
}
