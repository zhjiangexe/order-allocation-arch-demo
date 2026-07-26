package com.flowzati.archone.allocation.entrypoint.rest;

import com.flowzati.archone.allocation.domain.model.StockPool;

/**
 * 單一 SKU 的庫存狀態。
 *
 * <p>{@code availableToPromise} 沿用領域方法的名稱，不在邊界上縮寫成 ATP——契約跟著領域
 * 語彙走，讀的人不需要另外查縮寫是什麼意思。
 */
public record StockPoolResponse(
    String sku,
    int onHandQuantity,
    int reservedQuantity,
    int availableToPromise
) {

  static StockPoolResponse from(StockPool stockPool) {
    return new StockPoolResponse(
        stockPool.getSku(),
        stockPool.getOnHandQuantity(),
        stockPool.getReservedQuantity(),
        stockPool.availableToPromise());
  }
}
