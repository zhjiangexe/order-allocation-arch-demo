package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.messaging;

public class SalesOrderPlacedTranslator {
    public String translate(
            com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced event) {
        return event.orderId();
    }
}
