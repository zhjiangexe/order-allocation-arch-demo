package com.flowzati.archone.inventory.position.receipt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import com.flowzati.archone.inventory.position.application.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.position.infrastructure.messaging.StockAvailabilityIncreasedIntegrationEventAdapter;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockAvailabilityIncreasedIntegrationEventAdapterTest {

    @Test
    void mapsApplicationAvailabilityFactToExistingV1ContractAndContentionRouting() {
        IntegrationEventPublisher integrationEventPublisher = mock(IntegrationEventPublisher.class);
        var adapter = new StockAvailabilityIncreasedIntegrationEventAdapter(integrationEventPublisher);
        Instant occurredAt = Instant.parse("2026-08-27T04:00:00Z");
        StockAvailabilityIncreased increase =
                new StockAvailabilityIncreased(uuid(1), uuid(2), uuid(3), "SKU-A", 10, occurredAt);

        adapter.publish(increase);

        ArgumentCaptor<StockAvailabilityIncreasedIntegrationEvent> event =
                ArgumentCaptor.forClass(StockAvailabilityIncreasedIntegrationEvent.class);
        ArgumentCaptor<AggregateReference> aggregate = ArgumentCaptor.forClass(AggregateReference.class);
        ArgumentCaptor<PublicationTarget> target = ArgumentCaptor.forClass(PublicationTarget.class);
        verify(integrationEventPublisher)
                .publish(event.capture(), aggregate.capture(), target.capture(), eq(occurredAt));
        assertThat(event.getValue().getOwnerId()).isEqualTo(uuid(1));
        assertThat(event.getValue().getFacilityId()).isEqualTo(uuid(2));
        assertThat(event.getValue().getLocationId()).isEqualTo(uuid(3));
        assertThat(event.getValue().getSku()).isEqualTo("SKU-A");
        assertThat(event.getValue().getQuantity()).isEqualTo(10);
        String contentionKey = StockContentionKey.of(uuid(1), uuid(2));
        assertThat(aggregate.getValue())
                .isEqualTo(new AggregateReference(InventoryAggregateTypes.STOCK_POOL, contentionKey));
        assertThat(target.getValue()).isEqualTo(new PublicationTarget(InventoryChannels.STOCK_EVENTS, contentionKey));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
