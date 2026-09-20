package com.flowzati.archone.orderfulfillment.application.port;

import com.flowzati.archone.orderfulfillment.application.event.OrderingCancellationRequested;

public interface OrderingCancellationRequestedPublisher {
    void publish(OrderingCancellationRequested event);
}
