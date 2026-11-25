package com.flowzati.archone.architecture.fixture.violation.splitpublisher.application.port;

public interface ShipmentPublisher {
    void publish(String snapshot, String action);
}
