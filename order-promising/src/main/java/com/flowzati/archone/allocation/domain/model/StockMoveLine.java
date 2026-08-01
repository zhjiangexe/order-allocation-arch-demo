package com.flowzati.archone.allocation.domain.model;

import java.util.UUID;

/**
 * 一段搬運實際從哪一批取用。
 *
 * <p>粒度是「搬運 × 批」：一段跨三批就是三列。曾考慮「一列內含批次清單」，否決的理由是釋放
 * 與消耗都逐批發生（出貨時某一批先被揀完），一列多批表達不了部分消耗。
 *
 * <p><b>刻意沒有自己的狀態。</b>它的狀態就是所屬搬運的狀態。而「已釋放」不是一個狀態——
 * <b>釋放是刪除這一列</b>：一條被釋放的預留不表達任何事實，貨沒有動，也沒有被鎖住。留著它
 * 等於讓每個讀取端都要記得過濾。
 *
 * <p>代價：釋放的歷史不留在這裡，它留在搬運的狀態轉換上。
 */
public record StockMoveLine(UUID id, UUID moveId, UUID stockPoolId, int quantity) {

  public StockMoveLine {
    if (id == null || moveId == null || stockPoolId == null) {
      throw new IllegalArgumentException("Move line requires an id, a move and a stock pool");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Move line quantity must be positive");
    }
  }
}
