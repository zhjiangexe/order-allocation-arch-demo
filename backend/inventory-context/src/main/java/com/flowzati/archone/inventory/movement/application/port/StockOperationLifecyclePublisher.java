package com.flowzati.archone.inventory.movement.application.port;

import com.flowzati.archone.inventory.movement.application.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.domain.StockOperationLifecycleAction;

/** Publishes a stock-operation lifecycle fact without exposing an integration-event contract to application code. */
@FunctionalInterface
public interface StockOperationLifecyclePublisher {

    void publish(StockOperationLifecycleSnapshot snapshot, StockOperationLifecycleAction action);
}
