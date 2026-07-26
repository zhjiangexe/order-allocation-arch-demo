package com.flowzati.archone.common.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox row 把兩類資訊分欄表達，兩者互不兼任：
 *
 * <ul>
 *   <li>領域身分：{@code aggregateType} 與 {@code aggregateId} 回答「這筆事件屬於哪個
 *       aggregate」。</li>
 *   <li>傳輸決策：{@code route} 與 {@code partitionKey} 回答「這則訊息去哪個 topic、
 *       用什麼 key 分區」。</li>
 * </ul>
 *
 * <p>Debezium 只從傳輸欄位取值（{@code route.by.field=route}、
 * {@code table.field.event.key=partition_key}），不讀取領域欄位；因此分區策略切換不會
 * 改變 outbox 對事件所屬 aggregate 的答案。
 */
public record Outbox(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    String route,
    String partitionKey,
    String payload,
    Instant occurredAt
) {
  public Outbox {
    if (eventId == null || aggregateType == null || aggregateType.isBlank()
        || aggregateId == null || aggregateId.isBlank() || eventType == null || eventType.isBlank()
        || route == null || route.isBlank() || partitionKey == null || partitionKey.isBlank()
        || payload == null || payload.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("Outbox event fields are required");
    }
  }
}
