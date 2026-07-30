package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetStockPoolUsecase {

  private final StockPoolRepository stockPoolRepository;

  public GetStockPoolUsecase(StockPoolRepository stockPoolRepository) {
    this.stockPoolRepository = stockPoolRepository;
  }

  /**
   * 某貨主在某倉、某 SKU 的所有批——**含已過期的**。
   *
   * <p>過期的批要留在結果裡並標記，不能靜默略過：「有 100 件但一件都出不了」與
   * 「什麼都沒有」在畫面上必須分得出來，因為前者要報廢、後者要進貨。
   *
   * <p>一批都沒有時丟 {@link NoSuchElementException}，由 HTTP 層轉成 404。這裡的「沒有」與
   * 配貨路徑上的「沒有」是不同的事：配貨的缺貨是正常結果，查詢的查無則是使用者問了一個不
   * 存在的組合。
   */
  public List<StockPool> getBatches(UUID ownerId, String skuCode) {
    List<StockPool> batches = stockPoolRepository.findBatchesAcrossNodes(ownerId, skuCode);
    if (batches.isEmpty()) {
      throw new NoSuchElementException(
          "No stock held for SKU " + skuCode + " under owner " + ownerId);
    }
    return batches;
  }
}
