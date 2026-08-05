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
 * <p><b>但分組不用它。</b>ship-complete 的整單判斷以 {@code pickingId} 分組；
 * {@code orderId} 用來把沒有訂單的 inbound picking 排除在待配佇列之外，並在配到後
 * 建立結果事件。
 *
 * <p><b>狀態是底下 moves 的物化摘要。</b>它不是另一套獨立生命週期：建立時為
 * {@link PickingState#CONFIRMED}，moves 全部鎖定時進 {@link PickingState#ASSIGNED}，完成或
 * 取消也與 moves 在同一個 transaction 更新。物化的目的是讓作業單列表可以直接篩選與排程。
 *
 * <p>一旦狀態可寫，配貨與取消就可能同時修改同一張單，因此 picking 也必須帶 optimistic-lock
 * {@code version}，不能再依賴「只有建立時寫一次」的舊假設。
 */
public class StockPicking {

  private final UUID id;
  private final UUID pickingTypeId;
  private final UUID ownerId;
  private final UUID orderId;
  private final UUID fromLocationId;
  private final UUID toLocationId;
  private PickingState state;
  private final Long version;

  public StockPicking(
      UUID id,
      UUID pickingTypeId,
      UUID ownerId,
      UUID orderId,
      UUID fromLocationId,
      UUID toLocationId,
      PickingState state,
      Long version
  ) {
    if (id == null || pickingTypeId == null || ownerId == null) {
      throw new IllegalArgumentException("Picking requires an id, a type and an owner");
    }
    if (fromLocationId == null || toLocationId == null) {
      throw new IllegalArgumentException("A picking must say where the work runs between");
    }
    if (state == null) {
      throw new IllegalArgumentException("Picking state is required");
    }
    this.id = id;
    this.pickingTypeId = pickingTypeId;
    this.ownerId = ownerId;
    this.orderId = orderId;
    this.fromLocationId = fromLocationId;
    this.toLocationId = toLocationId;
    this.state = state;
    this.version = version;
  }

  public static StockPicking confirmed(
      UUID id,
      UUID pickingTypeId,
      UUID ownerId,
      UUID orderId,
      UUID fromLocationId,
      UUID toLocationId
  ) {
    return new StockPicking(
        id, pickingTypeId, ownerId, orderId, fromLocationId, toLocationId,
        PickingState.CONFIRMED, null);
  }

  public boolean assign() {
    if (state == PickingState.ASSIGNED) {
      return false;
    }
    if (state != PickingState.CONFIRMED) {
      throw new IllegalStateException("Only a confirmed picking can be assigned, was " + state);
    }
    state = PickingState.ASSIGNED;
    return true;
  }

  public boolean complete() {
    if (state == PickingState.DONE) {
      return false;
    }
    if (state != PickingState.ASSIGNED) {
      throw new IllegalStateException("Only an assigned picking can be completed, was " + state);
    }
    state = PickingState.DONE;
    return true;
  }

  public boolean cancel() {
    if (state == PickingState.CANCELLED) {
      return false;
    }
    if (state == PickingState.DONE) {
      throw new IllegalStateException("A completed picking cannot be cancelled");
    }
    state = PickingState.CANCELLED;
    return true;
  }

  public UUID id() {
    return id;
  }

  public UUID pickingTypeId() {
    return pickingTypeId;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public UUID orderId() {
    return orderId;
  }

  public UUID fromLocationId() {
    return fromLocationId;
  }

  public UUID toLocationId() {
    return toLocationId;
  }

  public PickingState state() {
    return state;
  }

  public Long version() {
    return version;
  }
}
