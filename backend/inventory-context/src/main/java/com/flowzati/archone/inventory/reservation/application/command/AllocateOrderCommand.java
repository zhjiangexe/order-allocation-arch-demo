package com.flowzati.archone.inventory.reservation.application.command;

import java.util.UUID;

/**
 * Allocation context 內的訂單配置意圖。
 */
public record AllocateOrderCommand(UUID orderId) {

    public AllocateOrderCommand {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
    }
}
