package com.flowzati.archone.inventory.reservation.entrypoint.consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.inventory.reservation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.reservation.application.usecase.AllocateOrderUsecase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationOrderPlacedEventConsumerTest {

    @Test
    void shouldTranslateOrderPlacedEventToAllocationCommand() {
        AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
        AllocationOrderPlacedEventConsumer consumer = new AllocationOrderPlacedEventConsumer(allocateOrderUsecase);
        UUID orderId = UUID.randomUUID();

        consumer.onOrderPlaced(
                new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, Instant.parse("2026-08-10T02:00:00Z")));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
    }
}
