package com.flowzati.archone.architecture.fixture.violation.applicationeventadaptername.application.port;

public interface OrderPlacedPublisher {
    void publish(String orderId);
}
