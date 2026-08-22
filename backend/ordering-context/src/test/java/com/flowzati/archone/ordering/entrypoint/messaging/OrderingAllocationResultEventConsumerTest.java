package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingAllocationResultEventConsumerTest {

    @Test
    void shouldTranslateAllocationResultsToOrderingCommands() {
        RecordOrderAllocationUsecase allocationUsecase = mock(RecordOrderAllocationUsecase.class);
        OrderingAllocationResultEventConsumer consumer = new OrderingAllocationResultEventConsumer(allocationUsecase);
        UUID orderId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-10T02:00:00Z");

        consumer.onOrderAllocationCommitted(new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AllocationLine(
                        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 1)),
                occurredAt.plusSeconds(3600),
                50,
                occurredAt));
        verify(allocationUsecase).execute(new RecordOrderAllocationCommand(orderId, occurredAt));
    }
}
