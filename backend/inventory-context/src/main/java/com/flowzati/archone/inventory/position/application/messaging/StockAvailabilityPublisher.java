package com.flowzati.archone.inventory.position.application.messaging;

import com.flowzati.archone.inventory.position.application.StockAvailabilityIncrease;

/** Publishes newly available stock without coupling the receipt use case to messaging contracts. */
@FunctionalInterface
public interface StockAvailabilityPublisher {

    void publish(StockAvailabilityIncrease increase);
}
