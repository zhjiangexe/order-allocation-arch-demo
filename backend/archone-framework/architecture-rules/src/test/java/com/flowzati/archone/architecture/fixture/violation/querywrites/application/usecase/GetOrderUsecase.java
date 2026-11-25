package com.flowzati.archone.architecture.fixture.violation.querywrites.application.usecase;

import com.flowzati.archone.architecture.fixture.violation.querywrites.application.store.OrderStore;

public class GetOrderUsecase {
    private final OrderStore orderStore;

    public GetOrderUsecase(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    public String get(String orderId) {
        orderStore.markViewed(orderId);
        return orderStore.findById(orderId);
    }
}
