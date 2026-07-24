package com.flowzati.archone.allocation.application.command;

import java.util.UUID;

/**
 * Promising bounded context 內的訂單配置意圖。
 */
public record AllocateOrderCommand(UUID orderId) {

  public AllocateOrderCommand {
    if (orderId == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
  }
}
