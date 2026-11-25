package com.flowzati.archone.architecture.fixture.violation.applicationeventadaptername.infrastructure.event;

import com.flowzati.archone.architecture.fixture.violation.applicationeventadaptername.application.port.OrderPlacedPublisher;

/** 以下層機制命名，而不是 *ApplicationEventAdapter。 */
public class SpringOrderEventPublisher implements OrderPlacedPublisher {
    @Override
    public void publish(String orderId) {}
}
