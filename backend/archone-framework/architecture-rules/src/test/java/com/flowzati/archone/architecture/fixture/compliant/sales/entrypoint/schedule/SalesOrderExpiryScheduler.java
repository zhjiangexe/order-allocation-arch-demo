package com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.schedule;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase.ExpireSalesOrdersInteractor;

public class SalesOrderExpiryScheduler {
    private final ExpireSalesOrdersInteractor expireSalesOrdersInteractor;

    public SalesOrderExpiryScheduler(ExpireSalesOrdersInteractor expireSalesOrdersInteractor) {
        this.expireSalesOrdersInteractor = expireSalesOrdersInteractor;
    }

    public void run() {
        expireSalesOrdersInteractor.execute();
    }
}
