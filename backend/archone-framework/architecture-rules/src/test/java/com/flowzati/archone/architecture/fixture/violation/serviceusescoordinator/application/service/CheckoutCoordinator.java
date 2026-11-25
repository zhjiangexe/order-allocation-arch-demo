package com.flowzati.archone.architecture.fixture.violation.serviceusescoordinator.application.service;

public class CheckoutCoordinator {
    private final PricingService pricingService;

    public CheckoutCoordinator(PricingService pricingService) {
        this.pricingService = pricingService;
    }
}
