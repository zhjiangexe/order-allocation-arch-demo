package com.flowzati.archone.orderfulfillment.application.port;

import com.flowzati.archone.orderfulfillment.application.event.CancellationRequestAccepted;

public interface CancellationRequestAcceptedPublisher {
    void publish(CancellationRequestAccepted event);
}
