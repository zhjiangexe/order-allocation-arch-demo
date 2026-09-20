package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShipmentHandoverEventConsumerTest {

    @Test
    void normalizesCanonicalHandoverBeforeCompletingMovements() {
        CompleteOutboundMovementsUsecase completeMovements = mock(CompleteOutboundMovementsUsecase.class);
        ShipmentHandoverEventConsumer consumer = new ShipmentHandoverEventConsumer(completeMovements);
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant handedOverAt = Instant.parse("2026-08-19T10:00:00Z");
        var event = new ShipmentHandedOverIntegrationEvent(
                UUID.randomUUID(), shipmentId, stockOperationId, orderId, List.of(movementId), handedOverAt);
        consumer.onShipmentHandedOver(event);

        ArgumentCaptor<CompleteOutboundMovementsCommand> command =
                ArgumentCaptor.forClass(CompleteOutboundMovementsCommand.class);
        verify(completeMovements).execute(command.capture());
        assertThat(command.getValue().orderId()).isEqualTo(orderId);
        assertThat(command.getValue().shipmentId()).isEqualTo(shipmentId);
        assertThat(command.getValue().expectedStockOperationId()).isEqualTo(stockOperationId);
        assertThat(command.getValue().movementIds()).containsExactly(movementId);
        assertThat(command.getValue().completedAt()).isEqualTo(handedOverAt);
    }
}
