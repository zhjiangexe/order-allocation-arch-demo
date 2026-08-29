package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v3.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v2.InventoryAggregateTypes;
import com.flowzati.archone.inventory.movement.application.command.CompleteSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteSourceStockMovementsUsecase;
import com.flowzati.archone.inventory.movement.application.usecase.SourceStockMovementsCompletionResult;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShipmentHandoverEventConsumerTest {

    @Test
    void normalizesCanonicalHandoverBeforeCompletingMovements() {
        CompleteSourceStockMovementsUsecase completeMovements = mock(CompleteSourceStockMovementsUsecase.class);
        IntegrationEventPublisher eventPublisher = mock(IntegrationEventPublisher.class);
        ShipmentHandoverEventConsumer consumer = new ShipmentHandoverEventConsumer(completeMovements, eventPublisher);
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant handedOverAt = Instant.parse("2026-08-19T10:00:00Z");
        var event = new com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent(
                UUID.randomUUID(), shipmentId, stockOperationId, orderId, List.of(movementId), handedOverAt);
        when(completeMovements.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SourceStockMovementsCompletionResult(stockOperationId, true));

        consumer.onShipmentHandedOver(event);

        ArgumentCaptor<CompleteSourceStockMovementsCommand> command =
                ArgumentCaptor.forClass(CompleteSourceStockMovementsCommand.class);
        verify(completeMovements).execute(command.capture());
        assertThat(command.getValue().source().sourceId()).isEqualTo(orderId.toString());
        assertThat(command.getValue().moveIds()).containsExactly(movementId);
        assertThat(command.getValue().completedAt()).isEqualTo(handedOverAt);
        ArgumentCaptor<IntegrationEvent> publishedEvent = ArgumentCaptor.forClass(IntegrationEvent.class);
        ArgumentCaptor<AggregateReference> aggregate = ArgumentCaptor.forClass(AggregateReference.class);
        verify(eventPublisher)
                .publish(
                        publishedEvent.capture(),
                        aggregate.capture(),
                        org.mockito.ArgumentMatchers.any(PublicationTarget.class),
                        org.mockito.ArgumentMatchers.eq(handedOverAt));
        assertThat(publishedEvent.getValue()).isInstanceOf(OutboundMovementsCompletedIntegrationEvent.class);
        assertThat(aggregate.getValue().type()).isEqualTo(InventoryAggregateTypes.STOCK_OPERATION);
        assertThat(aggregate.getValue().id()).isEqualTo(stockOperationId.toString());
    }
}
