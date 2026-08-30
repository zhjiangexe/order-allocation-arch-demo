package com.flowzati.archone.inventory.movement.application.port;

/** Warehouse execution's durable answer to an Inventory cancellation request. */
public enum WarehouseCancellationDecision {
    CONFIRMED,
    REJECTED
}
