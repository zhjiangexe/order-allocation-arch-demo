package com.flowzati.archone.allocation.domain.service;

import java.time.Instant;

/**
 * 給挑單政策看的配貨額度。
 *
 * <p>{@code availableToPromise} 是**該 {@code (貨主, 倉, SKU)} 所有可配批的加總**，不是單一批
 * 的量。批之間對同一條需求是可互換的，政策要決定的是「這一輪能餵飽幾張單」，那個問題只跟
 * 總量有關；哪一張單吃到哪一批是之後 FEFO 的事。
 */
public record AllocationRequest(
    String sku,
    int availableToPromise,
    Instant decisionAt
) {
}
