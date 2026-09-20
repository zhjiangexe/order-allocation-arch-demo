package com.flowzati.archone.inventory.balance.infrastructure.messaging;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.balance.application.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.balance.application.port.StockAvailabilityIncreasedPublisher;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import org.springframework.stereotype.Component;

/** Translates a physical stock increase into the existing availability integration-event contract. */
@Component
public class StockAvailabilityIncreasedIntegrationEventAdapter implements StockAvailabilityIncreasedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockAvailabilityIncreasedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockAvailabilityIncreased event) {
        String contentionKey = StockContentionKey.of(event.ownerId(), event.facilityId());
        integrationEventPublisher.publish(
                new StockAvailabilityIncreasedIntegrationEvent(
                        IdGenerator.nextId(),
                        event.ownerId(),
                        event.facilityId(),
                        event.locationId(),
                        event.sku(),
                        event.quantity()),
                new AggregateReference(InventoryAggregateTypes.STOCK_POOL, contentionKey),
                new PublicationTarget(InventoryEventDestinations.STOCK_EVENTS, contentionKey),
                event.occurredAt());
    }
}
