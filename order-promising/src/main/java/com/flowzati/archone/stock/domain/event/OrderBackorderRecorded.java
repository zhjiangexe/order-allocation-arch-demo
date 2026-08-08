package com.flowzati.archone.stock.domain.event;

import com.flowzati.archone.promising.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 配貨判定這張單的需求現在滿足不了的領域事實。
 *
 * <p>與 {@link OrderAllocationCompleted} 對稱：兩者都由 coordinator 在自己的表寫完之後發出，
 * 是 allocation 對外陳述結果的唯二管道。
 *
 * <p>只帶識別與時間，理由同 {@link OrderAllocationCompleted}：消費端拿 orderId 重讀整張單。
 */
public record OrderBackorderRecorded(UUID orderId, Instant backorderedAt)
    implements DomainEvent {

  public OrderBackorderRecorded {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (backorderedAt == null) {
      throw new IllegalArgumentException("Backordered time is required");
    }
  }
}
