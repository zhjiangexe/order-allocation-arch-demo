package com.flowzati.archone.fulfillment.application.port;

import com.flowzati.archone.fulfillment.application.event.OrderingCancellationRequested;

public interface OrderingCancellationRequestedPublisher {
    void publish(OrderingCancellationRequested event);
}
