package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingAllocationResultEventConsumerTest {

    @Test
    void shouldTranslateAllocationResultsToOrderingCommands() {
        RecordOrderAllocationUsecase allocationUsecase = mock(RecordOrderAllocationUsecase.class);
        OrderingAllocationResultEventConsumer consumer = new OrderingAllocationResultEventConsumer(allocationUsecase);
        UUID orderId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-10T02:00:00Z");

        consumer.onOrderAllocated(new OrderAllocatedIntegrationEvent(UUID.randomUUID(), orderId, occurredAt));
        verify(allocationUsecase).execute(new RecordOrderAllocationCommand(orderId, occurredAt));
    }
}
