package com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.event;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced;
import com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation.ExpireSalesOrderCommand;
import com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase.ExpireSalesOrderUsecase;
import org.springframework.context.event.EventListener;

public class SalesOrderEventListener {
    private final ExpireSalesOrderUsecase expireSalesOrderUsecase;

    public SalesOrderEventListener(ExpireSalesOrderUsecase expireSalesOrderUsecase) {
        this.expireSalesOrderUsecase = expireSalesOrderUsecase;
    }

    @EventListener
    public void on(SalesOrderPlaced event) {
        expireSalesOrderUsecase.execute(new ExpireSalesOrderCommand(event.orderId()));
    }
}
