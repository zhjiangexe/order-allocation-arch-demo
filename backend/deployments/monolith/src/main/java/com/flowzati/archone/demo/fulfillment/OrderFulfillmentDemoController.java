package com.flowzati.archone.demo.fulfillment;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEMO 專用的跨 Context fulfillment read API。 */
@RestController
@RequestMapping("/demo/orders")
public class OrderFulfillmentDemoController {

    private final OrderFulfillmentQueryService queryService;

    public OrderFulfillmentDemoController(OrderFulfillmentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{orderId}/fulfillment")
    public OrderFulfillmentView getFulfillment(@PathVariable(name = "orderId") UUID orderId) {
        return queryService.query(orderId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
    }
}
