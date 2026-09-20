package com.flowzati.archone.inventory.movement.operation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.StockOperationLifecycleIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot.MoveLineSnapshot;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot.MoveSnapshot;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.messaging.StockOperationLifecycleChangedIntegrationEventAdapter;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockOperationLifecycleChangedIntegrationEventAdapterTest {

    @Test
    void mapsApplicationLifecycleFactToExistingV2ContractAndRouting() {
        IntegrationEventPublisher integrationEventPublisher = mock(IntegrationEventPublisher.class);
        var adapter = new StockOperationLifecycleChangedIntegrationEventAdapter(integrationEventPublisher);
        Instant occurredAt = Instant.parse("2026-08-27T03:00:00Z");
        StockOperationLifecycleSnapshot snapshot = new StockOperationLifecycleSnapshot(
                uuid(1),
                uuid(2),
                StockOperationSource.primaryOrder(uuid(3).toString()),
                List.of(new MoveSnapshot(
                        uuid(4), uuid(5).toString(), "SKU-A", 3, List.of(new MoveLineSnapshot(uuid(6), 3)))),
                occurredAt);

        adapter.publish(StockOperationLifecycleChanged.released(snapshot));

        ArgumentCaptor<IntegrationEventPublication> publication =
                ArgumentCaptor.forClass(IntegrationEventPublication.class);
        verify(integrationEventPublisher).publish(publication.capture());
        assertThat(publication.getValue().event())
                .isInstanceOfSatisfying(StockOperationLifecycleIntegrationEvent.class, event -> {
                    assertThat(event.getAction())
                            .isEqualTo(StockOperationLifecycleIntegrationEvent.LifecycleAction.RELEASED);
                    assertThat(event.getMoves().getFirst().batches().getFirst().stockQuantId())
                            .isEqualTo(uuid(6));
                });
        assertThat(publication.getValue().aggregate().type()).isEqualTo(InventoryAggregateTypes.STOCK_OPERATION);
        assertThat(publication.getValue().aggregate().id()).isEqualTo(uuid(1).toString());
        assertThat(publication.getValue().target().destination())
                .isEqualTo(InventoryEventDestinations.STOCK_OPERATION_EVENTS);
        assertThat(publication.getValue().target().partitionKey()).isEqualTo(uuid(1).toString());
        assertThat(publication.getValue().occurredAt()).isEqualTo(occurredAt);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
