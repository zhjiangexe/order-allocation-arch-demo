package com.flowzati.archone.inventory.position.infrastructure.messaging;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.position.application.StockAvailabilityIncrease;
import com.flowzati.archone.inventory.position.application.messaging.StockAvailabilityPublisher;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import org.springframework.stereotype.Component;

/** Translates a physical stock increase into the existing availability integration-event contract. */
@Component
public class StockAvailabilityPublisherAdapter implements StockAvailabilityPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockAvailabilityPublisherAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockAvailabilityIncrease increase) {
        String contentionKey = StockContentionKey.of(increase.ownerId(), increase.facilityId());
        integrationEventPublisher.publish(
                new StockAvailabilityIncreasedIntegrationEvent(
                        IdGenerator.nextId(),
                        increase.ownerId(),
                        increase.facilityId(),
                        increase.locationId(),
                        increase.sku(),
                        increase.quantity()),
                new AggregateReference(InventoryAggregateTypes.STOCK_POOL, contentionKey),
                new PublicationTarget(InventoryChannels.STOCK_EVENTS, contentionKey),
                increase.occurredAt());
    }
}
