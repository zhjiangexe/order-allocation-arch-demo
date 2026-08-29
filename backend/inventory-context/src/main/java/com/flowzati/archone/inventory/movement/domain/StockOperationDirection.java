package com.flowzati.archone.inventory.movement.domain;

/**
 * 一種作業把貨往哪個方向搬。
 *
 * <p>Odoo 的 base stock 也只有這三個；製造、維修、直運是別的模組以 {@code selection_add}
 * 加上去的。本系統不做那些。
 */
public enum StockOperationDirection {

    /** 從公司外面進來：供應商 → 內部位置。 */
    INBOUND,

    /** 往公司外面出去：內部位置 → 客戶。 */
    OUTBOUND,

    /** 公司內部之間。此階段沒有產生者，但方向的值域一次定完比較省事。 */
    INTERNAL
}
