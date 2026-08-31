package com.flowzati.archone.inventory.reservation.assignment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.event.StockOperationAssigned;
import com.flowzati.archone.inventory.allocation.application.result.AssignedMove;
import com.flowzati.archone.inventory.allocation.application.result.AssignedMoveLine;
import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.infrastructure.messaging.StockOperationAssignedIntegrationEventAdapter;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockOperationAssignedIntegrationEventAdapterTest {

    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T02:00:00Z");

    @Test
    void mapsOrderAssignmentToExistingV3ContractAndRouting() {
        IntegrationEventPublisher integrationEventPublisher = mock(IntegrationEventPublisher.class);
        var adapter = new StockOperationAssignedIntegrationEventAdapter(integrationEventPublisher);
        StockOperationAssignmentResult result = result(MovementSourceType.ORDER, uuid(2).toString());

        adapter.publish(StockOperationAssigned.from(result));

        ArgumentCaptor<IntegrationEventPublication> publication =
                ArgumentCaptor.forClass(IntegrationEventPublication.class);
        verify(integrationEventPublisher).publish(publication.capture());
        assertThat(publication.getValue().event())
                .isInstanceOfSatisfying(OrderAllocationCommittedIntegrationEvent.class, event -> {
                    assertThat(event.getStockOperationId()).isEqualTo(uuid(1));
                    assertThat(event.getOrderId()).isEqualTo(uuid(2));
                    assertThat(event.getMoves().getFirst().orderLineId()).isEqualTo(uuid(10));
                    assertThat(event.getMoves()
                                    .getFirst()
                                    .batchPicks()
                                    .getFirst()
                                    .stockQuantId())
                            .isEqualTo(uuid(20));
                });
        assertThat(publication.getValue().aggregate().type()).isEqualTo(OrderingAggregateTypes.ORDER);
        assertThat(publication.getValue().aggregate().id()).isEqualTo(uuid(2).toString());
        assertThat(publication.getValue().target().destination()).isEqualTo(AllocationChannels.ALLOCATION_EVENTS);
        assertThat(publication.getValue().target().partitionKey()).isEqualTo(uuid(2).toString());
        assertThat(publication.getValue().occurredAt()).isEqualTo(ASSIGNED_AT);
    }

    @Test
    void keepsNonOrderSourcesInsideInventoryUntilTheyHaveAnExplicitContract() {
        IntegrationEventPublisher integrationEventPublisher = mock(IntegrationEventPublisher.class);
        var adapter = new StockOperationAssignedIntegrationEventAdapter(integrationEventPublisher);

        adapter.publish(StockOperationAssigned.from(result(MovementSourceType.TRANSFER, "transfer-1")));

        verify(integrationEventPublisher, never()).publish(any(IntegrationEventPublication.class));
    }

    private static StockOperationAssignmentResult result(MovementSourceType sourceType, String sourceId) {
        return new StockOperationAssignmentResult(
                uuid(1),
                uuid(3),
                uuid(4),
                new StockOperationSource(sourceType, sourceId, "PRIMARY"),
                uuid(5),
                uuid(6),
                uuid(7),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                ASSIGNED_AT.plusSeconds(3600),
                50,
                ASSIGNED_AT,
                List.of(new AssignedMove(
                        uuid(8), uuid(10).toString(), 1, "SKU-A", 3, List.of(new AssignedMoveLine(uuid(20), 3)))));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
