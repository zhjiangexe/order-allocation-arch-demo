package com.flowzati.archone.ordering.application.port;

import com.flowzati.archone.ordering.application.event.OrderCancellationResolved;

public interface OrderCancellationResolvedPublisher {
    void publish(OrderCancellationResolved event);
}
