package com.flowzati.archone.architecture.fixture.violation.querywrites.application.store;

public interface OrderStore {
    String findById(String orderId);

    void markViewed(String orderId);
}
