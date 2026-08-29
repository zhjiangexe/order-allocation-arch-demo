package com.flowzati.archone.inventory.reservation.application.messaging;

import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;

/** Publishes the durable external fact produced by a committed stock-operation assignment. */
@FunctionalInterface
public interface StockOperationAssignmentPublisher {

    void publish(StockOperationAssignmentResult result);
}
