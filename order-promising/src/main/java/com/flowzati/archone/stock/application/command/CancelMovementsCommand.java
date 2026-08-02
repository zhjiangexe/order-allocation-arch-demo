package com.flowzati.archone.stock.application.command;

import java.util.UUID;

/**
 * 釋放指定訂單目前 ACTIVE reservation 的業務意圖。
 */
public record CancelMovementsCommand(UUID orderId) {

  public CancelMovementsCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
  }
}
