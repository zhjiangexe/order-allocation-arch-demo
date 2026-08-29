package com.flowzati.archone.inventory.reservation.infrastructure.messaging;

import com.flowzati.archone.inventory.movement.domain.MovementSourceType;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.reservation.application.messaging.StockOperationAssignmentPublisher;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import org.springframework.stereotype.Component;

/** Translates committed Inventory assignment results to versioned outbound integration events. */
@Component
public class StockOperationAssignmentPublisherAdapter implements StockOperationAssignmentPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockOperationAssignmentPublisherAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockOperationAssignmentResult result) {
        if (result.source().sourceType() == MovementSourceType.ORDER) {
            integrationEventPublisher.publish(OrderStockOperationAssignedPublicationFactory.create(result));
        }
    }
}
