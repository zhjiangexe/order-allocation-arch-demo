package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.foundation.error.NotFoundException;
import com.flowzati.archone.ordering.application.error.OrderApplicationErrorCode;
import com.flowzati.archone.ordering.application.invocation.GetOrderQuery;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import org.springframework.stereotype.Service;

@Service
public class GetOrderUsecase {

    private final OrderStore orderStore;

    public GetOrderUsecase(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    public Order getOrder(GetOrderQuery query) {
        return orderStore
                .findById(query.orderId())
                .orElseThrow(() -> new NotFoundException(
                        OrderApplicationErrorCode.ORDER_NOT_FOUND, "Order not found: " + query.orderId()));
    }
}
