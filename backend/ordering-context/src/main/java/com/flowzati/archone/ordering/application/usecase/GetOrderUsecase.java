package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetOrderUsecase {

    private final OrderStore orderStore;

    public GetOrderUsecase(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    public Order getOrder(UUID orderId) {
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
    }
}
