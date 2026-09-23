package com.flowzati.archone.fulfillment.entrypoint.rest;

import com.flowzati.archone.fulfillment.application.usecase.OrderFulfillmentQueryUsecase;
import com.flowzati.archone.fulfillment.entrypoint.rest.response.OrderFulfillmentView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEMO 專用的跨 Context fulfillment read API。 */
@RestController
@RequestMapping("/demo/orders")
public class OrderFulfillmentDemoRest {

    private final OrderFulfillmentQueryUsecase queryService;

    public OrderFulfillmentDemoRest(OrderFulfillmentQueryUsecase queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{orderId}/fulfillment")
    public OrderFulfillmentView getFulfillment(@PathVariable(name = "orderId") UUID orderId) {
        var result = queryService.query(orderId);
        return new OrderFulfillmentView(
                result.order(),
                result.stockOperation(),
                result.shipments(),
                result.temporalWorkflow(),
                result.workflowQueryStatus());
    }
}
