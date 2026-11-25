package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.event;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced;
import com.flowzati.archone.architecture.fixture.compliant.sales.application.port.SalesOrderPlacedPublisher;
import org.springframework.context.ApplicationEventPublisher;

/** 同一 context 內的 Application Event 發布：交給 entrypoint.event 的 Listener。 */
public class SalesApplicationEventAdapter implements SalesOrderPlacedPublisher {
    private final ApplicationEventPublisher events;

    public SalesApplicationEventAdapter(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(SalesOrderPlaced event) {
        events.publishEvent(event);
    }
}
