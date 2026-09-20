package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingFulfillmentCompletionEventConsumerTest {

    @Test
    void keepsShipmentCorrelationWhenMappingTheCompletionFact() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-08-19T10:00:00Z");
        RecordOrderFulfillmentUsecase usecase = mock(RecordOrderFulfillmentUsecase.class);
        OrderingFulfillmentCompletionEventConsumer consumer = new OrderingFulfillmentCompletionEventConsumer(usecase);

        consumer.onOutboundMovementsCompleted(new OutboundMovementsCompletedIntegrationEvent(
                UUID.randomUUID(), UUID.randomUUID(), orderId, shipmentId, List.of(UUID.randomUUID()), completedAt));

        verify(usecase).execute(new RecordOrderFulfillmentCommand(orderId, shipmentId, completedAt));
    }
}
