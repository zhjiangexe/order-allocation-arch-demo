package com.flowzati.archone.stock.allocation.domain.type;

/**
 * 一次配貨的結果，以及配不到時的理由。
 *
 * <p><b>這裡只放領域服務自己算得出來的理由。</b>「有批但全部過期」曾經是這個 enum 的第四個
 * 值，由應用層多查一次完整批次清單補上——移除了，因為那個區別屬於**庫存狀態而不是訂單狀態**：
 * 它會隨著補貨與報廢每天變，而訂單進入等待供應後不再把理由固定寫死。庫存頁每次請求重算，答的是「現在為什麼出不了」，那才
 * 是要行動的人需要的。
 */
public enum AllocationOutcome {

  ALLOCATED,

  /** 有可配的批，但加總起來仍不足以整單滿足（ship-complete，不做部分配貨）。 */
  INSUFFICIENT_ATP,

  /** 可用庫存必須先提供給同一 owner/location/SKU 上更早進入佇列的需求。 */
  WAITING_FOR_EARLIER_DEMAND,

  /**
   * 沒有任何一批可配。
   *
   * <p>可能是一批都沒有、全部過期，或全部被預留光——三者對配貨而言沒有差別，而它們的差別
   * 看庫存頁（那裡逐批列出效期與可承諾量）。
   */
  NO_ALLOCATABLE_STOCK
}
