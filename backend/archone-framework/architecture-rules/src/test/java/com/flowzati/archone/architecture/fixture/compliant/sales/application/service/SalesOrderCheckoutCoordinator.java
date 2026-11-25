package com.flowzati.archone.architecture.fixture.compliant.sales.application.service;

public class SalesOrderCheckoutCoordinator {
    private final SalesOrderPricingService salesOrderPricingService;

    public SalesOrderCheckoutCoordinator(SalesOrderPricingService salesOrderPricingService) {
        this.salesOrderPricingService = salesOrderPricingService;
    }

    public int checkout(int quantity) {
        return salesOrderPricingService.price(quantity);
    }
}
