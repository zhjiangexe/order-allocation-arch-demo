package com.flowzati.archone.stock.domain.service;

import com.flowzati.archone.stock.domain.model.StockPool;
import java.util.UUID;

/**
 * 「這一條訂單行從這一批取這麼多」——配貨演算法的最小輸出單位。
 *
 * <p>粒度是行 × 批而不是訂單 × 批：一條行吃到三個批就是三個 pick，對應三筆預留。釋放與消耗
 * 都是逐批發生的，少了這個粒度就無法表達部分消耗。
 */
public record BatchPick(UUID orderLineId, StockPool batch, int quantity) {

  public BatchPick {
    if (orderLineId == null) {
      throw new IllegalArgumentException("Order line ID is required");
    }
    if (batch == null) {
      throw new IllegalArgumentException("Batch is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Pick quantity must be positive");
    }
  }
}
