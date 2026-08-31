package com.flowzati.archone.inventory.allocation.application.port;

import com.flowzati.archone.inventory.allocation.application.event.StockOperationAssigned;

/** Publishes the durable external fact produced by a committed stock-operation assignment. */
@FunctionalInterface
public interface StockOperationAssignedPublisher {

    void publish(StockOperationAssigned event);
}
