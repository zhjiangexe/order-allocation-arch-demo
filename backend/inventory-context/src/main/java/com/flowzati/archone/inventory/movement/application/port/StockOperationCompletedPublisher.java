package com.flowzati.archone.inventory.movement.application.port;

import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;

/** Publishes the single application fact produced by outbound Stock Operation completion. */
@FunctionalInterface
public interface StockOperationCompletedPublisher {

    void publish(StockOperationCompleted event);
}
