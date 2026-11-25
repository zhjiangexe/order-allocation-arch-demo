package com.flowzati.archone.architecture.fixture.violation.entrypointusesservice.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.violation.entrypointusesservice.application.service.PricingService;

public class PriceRest {
    private final PricingService pricingService;

    public PriceRest(PricingService pricingService) {
        this.pricingService = pricingService;
    }
}
