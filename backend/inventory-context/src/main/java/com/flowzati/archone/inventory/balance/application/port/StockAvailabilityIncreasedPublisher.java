package com.flowzati.archone.inventory.balance.application.port;

import com.flowzati.archone.inventory.balance.application.event.StockAvailabilityIncreased;

/** Publishes newly available stock without coupling the receipt use case to messaging contracts. */
@FunctionalInterface
public interface StockAvailabilityIncreasedPublisher {

    void publish(StockAvailabilityIncreased event);
}
