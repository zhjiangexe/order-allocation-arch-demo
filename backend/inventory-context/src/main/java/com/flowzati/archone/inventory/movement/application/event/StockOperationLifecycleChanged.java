package com.flowzati.archone.inventory.movement.application.event;

import java.util.Objects;

/** Complete application event for one durable Stock Operation lifecycle transition. */
public record StockOperationLifecycleChanged(
        StockOperationLifecycleAction action, StockOperationLifecycleSnapshot snapshot) {

    public StockOperationLifecycleChanged {
        Objects.requireNonNull(action, "Stock operation lifecycle action is required");
        Objects.requireNonNull(snapshot, "Stock operation lifecycle snapshot is required");
    }

    public static StockOperationLifecycleChanged released(StockOperationLifecycleSnapshot snapshot) {
        return new StockOperationLifecycleChanged(StockOperationLifecycleAction.RELEASED, snapshot);
    }

    public static StockOperationLifecycleChanged cancelled(StockOperationLifecycleSnapshot snapshot) {
        return new StockOperationLifecycleChanged(StockOperationLifecycleAction.CANCELLED, snapshot);
    }
}
