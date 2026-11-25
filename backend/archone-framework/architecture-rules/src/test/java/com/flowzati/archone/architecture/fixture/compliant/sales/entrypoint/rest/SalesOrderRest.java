package com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation.PlaceSalesOrderCommand;
import com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase.PlaceSalesOrderUsecase;
import com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.rest.request.PlaceSalesOrderRequest;
import com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.rest.response.SalesOrderResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalesOrderRest {

    private final PlaceSalesOrderUsecase usecase;

    public SalesOrderRest(PlaceSalesOrderUsecase usecase) {
        this.usecase = usecase;
    }

    public SalesOrderResponse place(PlaceSalesOrderRequest request) {
        usecase.place(new PlaceSalesOrderCommand(request.orderId(), List.of()));
        return new SalesOrderResponse(request.orderId());
    }
}
