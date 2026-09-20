package com.flowzati.archone.inventory.movement.application.event;

import java.util.Objects;
import java.util.UUID;

/** Inventory application fact produced after one outbound Stock Operation is durably completed. */
public record StockOperationCompleted(StockOperationLifecycleSnapshot snapshot, UUID orderId, UUID shipmentId) {

    public StockOperationCompleted {
        Objects.requireNonNull(snapshot, "Completed stock operation snapshot is required");
        Objects.requireNonNull(orderId, "Completed stock operation order ID is required");
        Objects.requireNonNull(shipmentId, "Completed stock operation shipment ID is required");
    }
}
