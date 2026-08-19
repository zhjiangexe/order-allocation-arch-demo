package com.flowzati.archone.inventory.movement.domain.type;

/**
 * 一張倉庫作業單目前是否具備執行條件。
 *
 * <p>值名與目前可達的 Odoo {@code stock.picking.state} 對齊：本系統建立即確認，尚未實作
 * 草稿與多段作業依賴，因此暫時沒有 {@code DRAFT} 與「等待上一道作業」。
 */
public enum PickingState {

  /** 作業已確認，但至少一個搬運仍在等待庫存。 */
  CONFIRMED,

  /** 所有需要的庫存都已鎖定，可以交給現場執行。 */
  ASSIGNED,

  /** 作業底下的搬運均已實際完成。 */
  DONE,

  /** 作業已取消，不再執行。 */
  CANCELLED
}
