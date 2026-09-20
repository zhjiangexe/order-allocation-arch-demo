package com.flowzati.archone.inventory.balance.entrypoint.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.inventory.balance.application.result.StockQuantView;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 某貨主在某倉手上的全部批，依 SKU 分組。
 *
 * <p>回的是批的清單而不是加總：一個規格分成幾批、每批的效期與過期與否，正是庫存頁展開之後
 * 要回答的問題。摺成加總之後，「有 100 件但一件都出不了」與「什麼都沒有」在畫面上長得一模
 * 一樣。**四個聚合數字由前端從這些批算**——它還要與主檔 left join 補上這個倉沒有的規格，
 * 兩件事在同一個地方做，就只有一份算法。
 *
 * <p>分組是清單而不是 {@code Map}：JSON 物件的鍵順序沒有保證，而查詢刻意把同一個 SKU 的批
 * 排在一起、組內依效期。用 map 表達等於把那個順序交給序列化去決定。
 *
 * <p>{@code availableToPromise} 沿用領域方法的名稱，不在邊界上縮寫成 ATP——契約跟著領域
 * 語彙走，讀的人不需要另外查縮寫是什麼意思。
 */
public record StockQuantResponse(List<SkuStockResponse> skus) {

    static StockQuantResponse from(List<StockQuantView> batches, LocalDate today) {
        Map<String, List<StockQuantView>> batchesBySku = batches.stream()
                .collect(Collectors.groupingBy(StockQuantView::skuCode, LinkedHashMap::new, Collectors.toList()));
        return new StockQuantResponse(batchesBySku.entrySet().stream()
                .map(entry -> SkuStockResponse.from(entry.getKey(), entry.getValue(), today))
                .toList());
    }

    /** 一個規格在這個倉的全部批，依效期、入庫日、id 排序——配貨會取用它們的順序。 */
    public record SkuStockResponse(String sku, List<StockBatchResponse> batches) {

        static SkuStockResponse from(String sku, List<StockQuantView> batches, LocalDate today) {
            return new SkuStockResponse(
                    sku,
                    batches.stream()
                            .map(batch -> StockBatchResponse.from(batch, today))
                            .toList());
        }
    }

    /**
     * 一批貨在畫面上的樣子。
     *
     * <p><b>沒有「為什麼不能配」的欄位。</b>曾經有一個 {@code unsellableReason}，但它永遠只會
     * 是 {@code null} 或 {@code "EXPIRED"}——為想像中的待驗、封鎖、破損預留，而那三者在動工前
     * 就已明確不做。要說的兩件事 {@code expired} 與 {@code availableToPromise} 各講一件，讀的
     * 人合起來就知道是「過期了」「還是被預留光了」，不需要第三個欄位轉述。
     *
     * <p><b>沒有 {@code facilityId}。</b>整份回應已經鎖在一個倉裡，每一批再帶一次只是把查詢參數
     * 抄回來。改動前它是必要的——那時一次回答跨所有倉。
     */
    public record StockBatchResponse(
            @JsonProperty("stockPoolId") UUID stockQuantId,
            LocalDate inDate,
            LocalDate expiryDate,
            int onHandQuantity,
            int reservedQuantity,
            int availableToPromise,
            boolean expired) {

        static StockBatchResponse from(StockQuantView batch, LocalDate today) {
            return new StockBatchResponse(
                    batch.stockQuantId(),
                    batch.inDate(),
                    batch.expiryDate(),
                    batch.onHandQuantity(),
                    batch.reservedQuantity(),
                    batch.availableToPromise(),
                    batch.isExpired(today));
        }
    }
}
