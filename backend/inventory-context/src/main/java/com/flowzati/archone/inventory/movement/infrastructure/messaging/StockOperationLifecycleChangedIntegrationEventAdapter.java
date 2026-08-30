package com.flowzati.archone.inventory.movement.infrastructure.messaging;

import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecycleChangedPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import org.springframework.stereotype.Component;

/** Translates application lifecycle facts to the versioned Inventory integration contract. */
@Component
public class StockOperationLifecycleChangedIntegrationEventAdapter implements StockOperationLifecycleChangedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockOperationLifecycleChangedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockOperationLifecycleChanged event) {
        integrationEventPublisher.publish(StockOperationLifecycleTranslator.translate(event));
    }
}
