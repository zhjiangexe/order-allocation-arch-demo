package com.flowzati.archone.architecture.fixture.compliant.sales.domain.valueobject;

public final class Money {

    private final long cents;

    public Money(long cents) {
        this.cents = cents;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money that && cents == that.cents;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(cents);
    }
}
