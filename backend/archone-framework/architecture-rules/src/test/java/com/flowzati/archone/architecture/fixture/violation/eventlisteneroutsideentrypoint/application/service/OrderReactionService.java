package com.flowzati.archone.architecture.fixture.violation.eventlisteneroutsideentrypoint.application.service;

import org.springframework.context.event.EventListener;

/** 名稱符合 application.service，但在這裡直接接收 Application Event，繞過 entrypoint.event。 */
public class OrderReactionService {
    @EventListener
    public void on(String orderPlaced) {}
}
