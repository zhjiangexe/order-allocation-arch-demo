package com.flowzati.archone.ordering.application.port;

import com.flowzati.archone.ordering.application.event.OrderCancelled;

/** Publishes the durable fact that an Order was cancelled. */
@FunctionalInterface
public interface OrderCancelledPublisher {

    void publish(OrderCancelled event);
}
