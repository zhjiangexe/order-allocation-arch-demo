package com.flowzati.archone.inventory.movement.infrastructure.messaging;

import com.flowzati.archone.inventory.movement.application.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecyclePublisher;
import com.flowzati.archone.inventory.movement.domain.StockOperationLifecycleAction;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import org.springframework.stereotype.Component;

/** Translates application lifecycle facts to the versioned Inventory integration contract. */
@Component
public class StockOperationLifecyclePublisherAdapter implements StockOperationLifecyclePublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockOperationLifecyclePublisherAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockOperationLifecycleSnapshot snapshot, StockOperationLifecycleAction action) {
        integrationEventPublisher.publish(StockOperationLifecyclePublicationFactory.create(snapshot, action));
    }
}
