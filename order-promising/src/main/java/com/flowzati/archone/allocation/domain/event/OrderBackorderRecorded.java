package com.flowzati.archone.allocation.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 配貨判定這張單的需求現在滿足不了的領域事實。
 *
 * <p>與 {@link OrderAllocationCompleted} 對稱：兩者都由 coordinator 在自己的表寫完之後發出，
 * 是 allocation 對外陳述結果的唯二管道。
 *
 * <p><b>這個事件取代了對 ordering 的 {@code OrderBackordered} 的監聽，而那不只是搬家。</b>
 * ordering 收到缺貨的對外事件之後會呼叫 {@code markBackOrdered()}，那會再產生一個
 * {@code OrderBackordered} 領域事件——若 translator 仍監聽它，就會再發一則對外事件、再被
 * ordering 消費、再產生一個領域事件，形成無限循環。
 *
 * <p>allocation 發自己的事實、ordering 發自己的，兩者不互相監聽，循環因此不可能形成。
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
