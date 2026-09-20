package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.ordering.application.invocation.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingAllocationResultEventConsumerTest {

    @Test
    void translatesStockOperationAssignmentToOrderingCommand() {
        RecordOrderAllocationUsecase allocationUsecase = mock(RecordOrderAllocationUsecase.class);
        OrderingAllocationResultEventConsumer consumer = new OrderingAllocationResultEventConsumer(allocationUsecase);
        UUID orderId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-10T02:00:00Z");

        consumer.onStockOperationAssigned(new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "SKU-1",
                        1,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 1)))),
                assignedAt.plusSeconds(3600),
                50,
                assignedAt));

        verify(allocationUsecase).execute(new RecordOrderAllocationCommand(orderId, assignedAt));
    }
}
