package com.flowzati.archone.allocation.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 這一輪喚醒到達了張數上限，佇列可能還有單——請對同一組庫存再來一輪。
 *
 * <p>這是領域事實而不是傳輸細節，所以由 usecase 發**領域事件**，再由 translator 譯成對外的
 * {@code BackorderWakeRequestedIntegrationEvent} 寫進 outbox。usecase 自己組對外事件、自己
 * append 也能跑，但那會讓它成為唯一一個知道 outbox 存在的 usecase——而 translator 這一層的
 * 用途正是「領域事實」與「怎麼送出去」只有一處交會。
 *
 * <p>帶的三個維度就是爭用群組：續做必須落回同一個 partition，否則它會與它要接續的那一輪並行，
 * 而 single writer 的保證正是靠同 key 取得的。{@code skuCode} 在這裡是必要的——它決定要喚醒
 * 哪個 SKU 的缺貨佇列，與 partition key 只取 {@code (貨主, 倉)} 是兩件不同的事。
 */
public record BackorderWakeContinuationRequired(
    UUID ownerId,
    UUID nodeId,
    String skuCode,
    Instant requestedAt
) implements DomainEvent {

  public BackorderWakeContinuationRequired {
    if (ownerId == null || nodeId == null) {
      throw new IllegalArgumentException("Owner ID and node ID are required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (requestedAt == null) {
      throw new IllegalArgumentException("Requested time is required");
    }
  }
}
