package com.flowzati.archone.demo.orderfulfillment.rest;

import com.flowzati.archone.demo.orderfulfillment.result.OrderFulfillmentView;
import com.flowzati.archone.demo.orderfulfillment.service.OrderFulfillmentQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEMO 專用的跨 Context fulfillment read API。 */
@RestController
@RequestMapping("/demo/orders")
public class OrderFulfillmentDemoRest {

    private final OrderFulfillmentQueryService queryService;

    public OrderFulfillmentDemoRest(OrderFulfillmentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{orderId}/fulfillment")
    public OrderFulfillmentView getFulfillment(@PathVariable(name = "orderId") UUID orderId) {
        return queryService.query(orderId);
    }
}
