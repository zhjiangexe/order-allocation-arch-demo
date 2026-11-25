package com.flowzati.archone.architecture.fixture.compliant.sales.domain.service;

import com.flowzati.archone.architecture.fixture.compliant.sales.domain.valueobject.Money;

public class TieredPriceCalculator implements PriceCalculator {

    private final long unitCents;

    public TieredPriceCalculator(long unitCents) {
        this.unitCents = unitCents;
    }

    @Override
    public Money price(int quantity) {
        return new Money(unitCents * quantity);
    }
}
