package com.flowzati.archone.allocation.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 一張倉庫作業單。**倉庫任務的真相。**
 *
 * <p>沒有 SKU、沒有數量、不直接影響庫存——它只說「這一趟作業從哪到哪、屬於誰」。數量的真相
 * 在底下的 {@link StockMove}。
 *
 * <p><b>沒有指向訂單的欄位。</b>「這張單據服務哪張訂單」由它底下 move 的 {@code orderLineId}
 * 推導。捷徑會成為第二個真相，而它與底下的 move 不一致時沒有規則說該信誰。
 *
 * <p><b>沒有狀態。</b>它由底下的 move 彙總，存起來就有兩份要對齊——而 ship-complete 下一張
 * 單的所有 move 同進同出，彙總本來就是 trivial 的。
 */
public record StockPicking(
    UUID id,
    UUID pickingTypeId,
    UUID ownerId,
    UUID fromLocationId,
    UUID toLocationId,
    String reference,
    Instant scheduledAt
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
