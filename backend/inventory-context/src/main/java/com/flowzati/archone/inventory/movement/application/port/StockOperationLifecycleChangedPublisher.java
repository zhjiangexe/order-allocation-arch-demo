package com.flowzati.archone.inventory.movement.application.port;

import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;

/** Publishes a stock-operation lifecycle fact without exposing an integration-event contract to application code. */
@FunctionalInterface
public interface StockOperationLifecycleChangedPublisher {

    void publish(StockOperationLifecycleChanged event);
}
