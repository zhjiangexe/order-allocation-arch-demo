package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.messaging;

public class SalesOrderPlacedIntegrationEventAdapter
        implements com.flowzati.archone.architecture.fixture.compliant.sales.application.port
                .SalesOrderPlacedPublisher {
    private final com.flowzati.archone.messaging.events.IntegrationEventPublisher integrationEventPublisher;
    private final SalesOrderPlacedTranslator translator = new SalesOrderPlacedTranslator();

    public SalesOrderPlacedIntegrationEventAdapter(
            com.flowzati.archone.messaging.events.IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(
            com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced event) {
        translator.translate(event);
        integrationEventPublisher.publishAll(java.util.List.of());
    }
}
