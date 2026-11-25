package com.flowzati.archone.architecture.fixture.violation.parameterlessfromrest.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.violation.parameterlessfromrest.application.usecase.ExpireOrdersUsecase;

public class OrderRest {
    private final ExpireOrdersUsecase expireOrdersUsecase;

    public OrderRest(ExpireOrdersUsecase expireOrdersUsecase) {
        this.expireOrdersUsecase = expireOrdersUsecase;
    }

    public void expire() {
        expireOrdersUsecase.execute();
    }
}
