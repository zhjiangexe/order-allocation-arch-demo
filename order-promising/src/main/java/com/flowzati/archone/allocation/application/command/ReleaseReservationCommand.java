package com.flowzati.archone.allocation.application.command;

import java.util.UUID;

/**
 * 釋放指定訂單目前 ACTIVE reservation 的業務意圖。
 */
public record ReleaseReservationCommand(UUID orderId) {

  public ReleaseReservationCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
  }
}
