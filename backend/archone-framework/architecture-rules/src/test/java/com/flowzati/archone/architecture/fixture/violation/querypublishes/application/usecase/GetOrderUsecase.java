package com.flowzati.archone.architecture.fixture.violation.querypublishes.application.usecase;

import com.flowzati.archone.architecture.fixture.violation.querypublishes.application.port.OrderViewedPublisher;

public class GetOrderUsecase {
    private final OrderViewedPublisher publisher;

    public GetOrderUsecase(OrderViewedPublisher publisher) {
        this.publisher = publisher;
    }

    public String get(String orderId) {
        publisher.publish(orderId);
        return orderId;
    }
}
