package com.flowzati.archone.inventory.reservation.application.port;

import com.flowzati.archone.inventory.reservation.application.event.StockOperationAssigned;

/** Publishes the durable external fact produced by a committed stock-operation assignment. */
@FunctionalInterface
public interface StockOperationAssignedPublisher {

    void publish(StockOperationAssigned event);
}
