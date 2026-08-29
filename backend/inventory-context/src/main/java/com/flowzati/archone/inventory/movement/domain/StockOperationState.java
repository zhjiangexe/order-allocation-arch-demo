package com.flowzati.archone.inventory.movement.domain;

/**
 * 一個 Inventory movement operation group 的物化摘要。
 *
 * <p>值名與目前可達的 Odoo {@code stock.operation.state} 對齊：本系統建立即確認，尚未實作
 * 草稿與多段作業依賴，因此暫時沒有 {@code DRAFT} 與「等待上一道作業」。
 */
public enum StockOperationState {

    /** 作業已確認，但至少一個搬運仍在等待庫存。 */
    CONFIRMED,

    /** 所有需要的庫存都已鎖定，可以把 movement snapshot 交給 WMS。 */
    ASSIGNED,

    /** 作業底下的搬運均已實際完成。 */
    DONE,

    /** 作業已取消，不再執行。 */
    CANCELLED
}
