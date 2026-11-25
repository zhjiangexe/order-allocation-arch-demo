package com.flowzati.archone.architecture.fixture.violation.querypublishes.application.port;

public interface OrderViewedPublisher {
    void publish(String orderId);
}
