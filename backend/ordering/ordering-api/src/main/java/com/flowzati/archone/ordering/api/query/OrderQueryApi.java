package com.flowzati.archone.ordering.api.query;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

public interface OrderQueryApi {

    @GetExchange("/internal/ordering/orders/{orderId}")
    OrderQueryView get(@PathVariable("orderId") UUID orderId);
}
