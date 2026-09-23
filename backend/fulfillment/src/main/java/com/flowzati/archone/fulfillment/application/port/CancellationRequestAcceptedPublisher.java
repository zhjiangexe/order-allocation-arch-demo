package com.flowzati.archone.fulfillment.application.port;

import com.flowzati.archone.fulfillment.application.event.CancellationRequestAccepted;

public interface CancellationRequestAcceptedPublisher {
    void publish(CancellationRequestAccepted event);
}
