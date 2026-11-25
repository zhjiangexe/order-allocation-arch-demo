package com.flowzati.archone.architecture.fixture.compliant.sales.domain.service;

import com.flowzati.archone.architecture.fixture.compliant.sales.domain.valueobject.Money;

public interface PriceCalculator {
    Money price(int quantity);
}
