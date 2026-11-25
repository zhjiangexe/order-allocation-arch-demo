package com.flowzati.archone.inventory.movement.application.enums;

/** Application-owned lifecycle action; integration-contract versions stay outside the application layer. */
public enum StockOperationLifecycleAction {
    RELEASED,
    COMPLETED,
    CANCELLED
}
