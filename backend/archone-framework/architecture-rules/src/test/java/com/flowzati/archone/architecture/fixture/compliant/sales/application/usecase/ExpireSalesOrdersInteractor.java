package com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation.ExpireSalesOrderCommand;

/** 排程批次：不收參數，逐筆交給 Usecase；只由 entrypoint.schedule 呼叫。 */
public class ExpireSalesOrdersInteractor {
    private final ListSalesOrdersUsecase listSalesOrdersUsecase;
    private final ExpireSalesOrderUsecase expireSalesOrderUsecase;

    public ExpireSalesOrdersInteractor(
            ListSalesOrdersUsecase listSalesOrdersUsecase, ExpireSalesOrderUsecase expireSalesOrderUsecase) {
        this.listSalesOrdersUsecase = listSalesOrdersUsecase;
        this.expireSalesOrderUsecase = expireSalesOrderUsecase;
    }

    public void execute() {
        listSalesOrdersUsecase.list().forEach(id -> expireSalesOrderUsecase.execute(new ExpireSalesOrderCommand(id)));
    }
}
