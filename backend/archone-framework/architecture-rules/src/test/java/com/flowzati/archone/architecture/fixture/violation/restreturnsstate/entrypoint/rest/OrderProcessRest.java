package com.flowzati.archone.architecture.fixture.violation.restreturnsstate.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.violation.restreturnsstate.application.state.OrderProcess;
import org.springframework.web.bind.annotation.RestController;

/** 直接把內部狀態物件當成 HTTP 回應。 */
@RestController
public class OrderProcessRest {
    public OrderProcess get(String orderId) {
        return new OrderProcess(orderId, "OPEN");
    }
}
