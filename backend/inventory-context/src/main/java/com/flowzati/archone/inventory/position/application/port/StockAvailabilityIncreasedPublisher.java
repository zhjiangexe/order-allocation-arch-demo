package com.flowzati.archone.inventory.position.application.port;

import com.flowzati.archone.inventory.position.application.event.StockAvailabilityIncreased;

/** Publishes newly available stock without coupling the receipt use case to messaging contracts. */
@FunctionalInterface
public interface StockAvailabilityIncreasedPublisher {

    void publish(StockAvailabilityIncreased event);
}
