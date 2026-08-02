package com.flowzati.archone.stock.domain.model;

import java.util.UUID;

/**
 * 一張倉庫作業單。**倉庫任務的真相。**
 *
 * <p>沒有 SKU、沒有數量、不直接影響庫存——它只說「這一趟作業從哪到哪、屬於誰」。數量的真相
 * 在底下的 {@link StockMove}。
 *
 * <p><b>{@code orderId} 可空</b>，與 {@link StockMove#getOrderLineId()} 對稱：入庫的單據背後
 * 沒有任何訂單。兩層都有連結，形狀取自 Odoo 19——{@code stock_picking.sale_id} 與
 * {@code stock_move.sale_line_id} 都存在，而 {@code stock_move} 沒有 {@code sale_id}。
 *
 * <p>它不是「第二個真相」那種捷徑：那個風險來自跨單合併（一張單據混進多張訂單的 move），而
 * 本系統明確不合併——一張出庫單就是一張單據。日後若真要合併，這個欄位必須拿掉，分組改由一個
 * 有自己身分的群組實體承擔。
 *
 * <p><b>但分組不用它。</b>ship-complete 的整單判斷以 {@code pickingId} 分組——單表 group by，
 * 補貨喚醒那條熱路徑因此一個 join 都沒有。訂單 id 只在配到之後要發事件時才讀。
 *
 * <p><b>沒有狀態。</b>它由底下的 move 彙總，存起來就有兩份要對齊——而 ship-complete 下一張
 * 單的所有 move 同進同出，彙總本來就是 trivial 的。
 */
public record StockPicking(
    UUID id,
    UUID pickingTypeId,
    UUID ownerId,
    UUID orderId,
    UUID fromLocationId,
    UUID toLocationId
) {

  public StockPicking {
    if (id == null || pickingTypeId == null || ownerId == null) {
      throw new IllegalArgumentException("Picking requires an id, a type and an owner");
    }
    if (fromLocationId == null || toLocationId == null) {
      throw new IllegalArgumentException("A picking must say where the work runs between");
    }
  }
}
