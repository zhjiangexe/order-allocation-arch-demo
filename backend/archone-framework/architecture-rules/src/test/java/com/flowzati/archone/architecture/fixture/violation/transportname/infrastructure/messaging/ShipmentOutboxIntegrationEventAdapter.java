package com.flowzati.archone.architecture.fixture.violation.transportname.infrastructure.messaging;

public class ShipmentOutboxIntegrationEventAdapter
        implements com.flowzati.archone.architecture.fixture.violation.transportname.application.port
                .ShipmentPublisher {
    com.flowzati.archone.messaging.events.IntegrationEventPublisher publisher;

    @Override
    public void publish(String event) {}
}
