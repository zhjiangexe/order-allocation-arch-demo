package com.flowzati.archone.architecture.fixture.compliant.sales.application.port;

public interface SalesOrderPlacedPublisher {
    void publish(com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced event);
}
