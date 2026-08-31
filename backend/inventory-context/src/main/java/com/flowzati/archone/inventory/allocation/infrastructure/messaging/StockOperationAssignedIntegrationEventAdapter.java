package com.flowzati.archone.inventory.allocation.infrastructure.messaging;

import com.flowzati.archone.inventory.allocation.application.event.StockOperationAssigned;
import com.flowzati.archone.inventory.allocation.application.port.StockOperationAssignedPublisher;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import org.springframework.stereotype.Component;

/** Translates committed Inventory assignment events to versioned outbound integration events. */
@Component
public class StockOperationAssignedIntegrationEventAdapter implements StockOperationAssignedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockOperationAssignedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockOperationAssigned event) {
        if (event.source().sourceType() == MovementSourceType.ORDER) {
            integrationEventPublisher.publish(OrderAllocationCommittedTranslator.translate(event));
        }
    }
}
