package com.flowzati.archone.allocation.entrypoint.rest;

import com.flowzati.archone.allocation.domain.model.StockPool;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 某貨主某 SKU 手上的所有批。
 *
 * <p>回的是清單而不是三個數字：一個 SKU 分成幾批、每批的效期與過期與否，正是庫存頁要回答的
 * 問題。摺成加總之後，「有 100 件但一件都出不了」與「什麼都沒有」在畫面上長得一模一樣。
 *
 * <p>{@code availableToPromise} 沿用領域方法的名稱，不在邊界上縮寫成 ATP——契約跟著領域
 * 語彙走，讀的人不需要另外查縮寫是什麼意思。
 */
public record StockPoolResponse(String sku, List<StockBatchResponse> batches) {

  static StockPoolResponse from(String sku, List<StockPool> batches, LocalDate today) {
    return new StockPoolResponse(
        sku,
        batches.stream().map(batch -> StockBatchResponse.from(batch, today)).toList());
  }

  /**
   * 一批貨在畫面上的樣子。
   *
   * <p><b>沒有「為什麼不能配」的欄位。</b>曾經有一個 {@code unsellableReason}，但它永遠只會
   * 是 {@code null} 或 {@code "EXPIRED"}——為想像中的待驗、封鎖、破損預留，而那三者在動工前
   * 就已明確不做。要說的兩件事 {@code expired} 與 {@code availableToPromise} 各講一件，讀的
   * 人合起來就知道是「過期了」「還是被預留光了」，不需要第三個欄位轉述。
   */
  public record StockBatchResponse(
      UUID stockPoolId,
      UUID nodeId,
      LocalDate inDate,
      LocalDate expiryDate,
      int onHandQuantity,
      int reservedQuantity,
      int availableToPromise,
      boolean expired
  ) {

    static StockBatchResponse from(StockPool batch, LocalDate today) {
      return new StockBatchResponse(
          batch.getId(),
          batch.getNodeId(),
          batch.getInDate(),
          batch.getExpiryDate(),
          batch.getOnHandQuantity(),
          batch.getReservedQuantity(),
          batch.availableToPromise(),
          batch.isExpired(today));
    }
  }
}
