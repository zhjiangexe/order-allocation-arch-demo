package com.flowzati.archone.ordering.application.command;

import java.time.Instant;
import java.util.UUID;

/** 缺貨的事實，要套到這張單上。理由同 {@link ConfirmAllocationCommand}。 */
public record RecordBackorderCommand(UUID orderId, Instant backorderedAt) {

  public RecordBackorderCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (backorderedAt == null) {
      throw new IllegalArgumentException("Backordered time is required");
    }
  }
}
