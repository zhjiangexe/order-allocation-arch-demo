package com.flowzati.archone.ordering.application.command;

import java.time.Instant;
import java.util.UUID;

/** stock context 的缺貨事實，要記錄到這張訂單。 */
public record RecordOrderBackorderCommand(UUID orderId, Instant backorderedAt) {

  public RecordOrderBackorderCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (backorderedAt == null) {
      throw new IllegalArgumentException("Backordered time is required");
    }
  }
}
