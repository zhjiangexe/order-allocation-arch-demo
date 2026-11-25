package com.flowzati.archone.architecture.fixture.violation.entrypointusesstore.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.violation.entrypointusesstore.application.store.OrderStore;

public class OrderRest {
    private final OrderStore orderStore;

    public OrderRest(OrderStore orderStore) {
        this.orderStore = orderStore;
    }
}
