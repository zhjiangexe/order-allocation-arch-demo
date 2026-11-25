package com.flowzati.archone.architecture.fixture.violation.eventpublisheroutsideadapter.application.usecase;

import org.springframework.context.ApplicationEventPublisher;

/** Usecase 直接用 Spring 發布事件，繞過 application.port 的 Publisher。 */
public class PlaceOrderUsecase {
    private final ApplicationEventPublisher events;

    public PlaceOrderUsecase(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void execute() {
        events.publishEvent("order placed");
    }
}
