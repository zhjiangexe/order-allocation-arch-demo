package com.flowzati.archone.inventory.movement.operation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v3.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v2.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v2.StockOperationLifecycleIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot.MoveLineSnapshot;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot.MoveSnapshot;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.infrastructure.messaging.StockOperationCompletedIntegrationEventAdapter;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOperationCompletedIntegrationEventAdapterTest {

    @Test
    void publishesLifecycleAuditAndFulfillmentFactFromOneCompletion() {
        List<IntegrationEventPublication> publications = new ArrayList<>();
        var adapter = new StockOperationCompletedIntegrationEventAdapter(publications::add);
        UUID stockOperationId = uuid(1);
        UUID orderId = uuid(2);
        UUID shipmentId = uuid(3);
        UUID movementId = uuid(4);
        UUID stockQuantId = uuid(5);
        Instant completedAt = Instant.parse("2026-08-30T03:30:00Z");
        var snapshot = new StockOperationLifecycleSnapshot(
                stockOperationId,
                uuid(6),
                StockOperationSource.primaryOrder(orderId.toString()),
                List.of(new MoveSnapshot(
                        movementId, "ORDER-LINE-1", "SKU-A", 3, List.of(new MoveLineSnapshot(stockQuantId, 3)))),
                completedAt);

        adapter.publish(new StockOperationCompleted(snapshot, orderId, shipmentId));

        assertThat(publications).hasSize(2);
        assertThat(publications.getFirst()).satisfies(publication -> {
            assertThat(publication.event())
                    .isInstanceOfSatisfying(StockOperationLifecycleIntegrationEvent.class, event -> {
                        assertThat(event.getAction())
                                .isEqualTo(StockOperationLifecycleIntegrationEvent.LifecycleAction.COMPLETED);
                        assertThat(event.getMoves()
                                        .getFirst()
                                        .batches()
                                        .getFirst()
                                        .stockQuantId())
                                .isEqualTo(stockQuantId);
                    });
            assertThat(publication.aggregate().type()).isEqualTo(InventoryAggregateTypes.STOCK_OPERATION);
            assertThat(publication.aggregate().id()).isEqualTo(stockOperationId.toString());
            assertThat(publication.target().destination()).isEqualTo(InventoryChannels.STOCK_OPERATION_EVENTS);
            assertThat(publication.occurredAt()).isEqualTo(completedAt);
        });
        assertThat(publications.get(1)).satisfies(publication -> {
            assertThat(publication.event())
                    .isInstanceOfSatisfying(OutboundMovementsCompletedIntegrationEvent.class, event -> {
                        assertThat(event.getStockOperationId()).isEqualTo(stockOperationId);
                        assertThat(event.getOrderId()).isEqualTo(orderId);
                        assertThat(event.getShipmentId()).isEqualTo(shipmentId);
                        assertThat(event.getMovementIds()).containsExactly(movementId);
                    });
            assertThat(publication.aggregate().type()).isEqualTo(InventoryAggregateTypes.STOCK_OPERATION);
            assertThat(publication.aggregate().id()).isEqualTo(stockOperationId.toString());
            assertThat(publication.target().destination()).isEqualTo(FulfillmentChannels.FULFILLMENT_HANDOFFS);
            assertThat(publication.target().partitionKey()).isEqualTo(orderId.toString());
            assertThat(publication.occurredAt()).isEqualTo(completedAt);
        });
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
