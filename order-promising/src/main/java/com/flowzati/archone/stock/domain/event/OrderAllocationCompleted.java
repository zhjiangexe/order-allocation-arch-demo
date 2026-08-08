package com.flowzati.archone.stock.domain.event;

import com.flowzati.archone.promising.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Order、StockPool 與 StockReservation 已共同完成完整配置的領域事實。
 *
 * <p><b>由 {@code MovementAssigner} 在三個聚合根都寫入之後發出</b>,不是由哪個聚合根在自己
 * 狀態轉換時發出。對外事件只能由它觸發。
 *
 * <p>只帶識別與時間。這個領域事件的唯一消費者是對外事件的 translator,而
 * {@code OrderAllocatedIntegrationEvent} 也只帶識別與時間,所以這裡沒有東西要帶。
 */
public record OrderAllocationCompleted(UUID orderId, Instant allocatedAt)
    implements DomainEvent {

  public OrderAllocationCompleted {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (allocatedAt == null) {
      throw new IllegalArgumentException("Allocated time is required");
    }
  }
}
