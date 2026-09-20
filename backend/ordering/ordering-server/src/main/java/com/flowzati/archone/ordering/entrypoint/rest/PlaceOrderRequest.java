package com.flowzati.archone.ordering.entrypoint.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 下單命令的 JSON request body。
 *
 * <p>欄位規則由 {@code Order} aggregate 與資料庫的約束驗證，不在此重複——包括「至少一筆行」，
 * 以及行的 SKU 必須存在於該貨主的主檔。行數沒有上限，同一個 SKU 也可以出現在多行上。
 *
 * <p><b>沒有收單時刻。</b>那是我們的事實，由 usecase 以系統時鐘寫入。呼叫端能提供的只有
 * {@code placedAt}——上游系統說客戶何時下的單。
 */
public record PlaceOrderRequest(
        @NotNull UUID ownerId,
        @NotBlank String externalOrderNo,
        @NotBlank String shipToZone,
        @NotBlank String shipToAddress,
        @NotNull LocalDate promisedDeliveryDate,
        /** 上游依承運時效、截單時間與行事曆算出的最晚離倉時刻。 */
        @NotNull Instant dispatchBy,
        /** WMS wave release 排程使用的必填 0..100 優先級；wrapper 用來區分缺欄位與明確的 0。 */
        @NotNull @Min(0) @Max(100) Integer releasePriority,
        @NotNull UUID facilityId,
        /**
         * 上游說客戶下單的時刻。可省略——上游系統沒有義務送這個值，省略時訂單就不帶它，
         * 不會被補成收單時刻。
         */
        Instant placedAt,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(@NotBlank String skuCode, @Positive int quantity) {}
}
