package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.configuration;

import com.flowzati.archone.architecture.fixture.compliant.sales.domain.service.PriceCalculator;
import com.flowzati.archone.architecture.fixture.compliant.sales.domain.service.TieredPriceCalculator;

public class SalesConfiguration {

    public PriceCalculator priceCalculator() {
        return new TieredPriceCalculator(100);
    }
}
