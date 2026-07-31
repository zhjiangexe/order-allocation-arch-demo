package com.flowzati.archone.ordering.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 配貨完成的事實，要套到這張單上。
 *
 * <p>只有識別碼與時間戳——狀態由 usecase 重讀訂單決定，事件不攜帶任何被當成狀態的東西。
 */
public record ConfirmAllocationCommand(UUID orderId, Instant allocatedAt) {

  public ConfirmAllocationCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (allocatedAt == null) {
      throw new IllegalArgumentException("Allocated time is required");
    }
  }
}
