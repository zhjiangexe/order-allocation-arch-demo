package com.flowzati.archone.ordering.application.port;

import com.flowzati.archone.ordering.application.event.OrderPlaced;

/** Publishes the durable fact that Ordering accepted an Order. */
@FunctionalInterface
public interface OrderPlacedPublisher {

    void publish(OrderPlaced event);
}
