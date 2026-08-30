package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.command.CancelSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CancelSourceStockMovementsUsecase;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationOrderCancellationEventConsumerTest {

    @Test
    void shouldTranslateOrderCancelledEventToCancellationCommand() {
        CancelSourceStockMovementsUsecase cancelMovementsUsecase = mock(CancelSourceStockMovementsUsecase.class);
        AllocationOrderCancellationEventConsumer consumer =
                new AllocationOrderCancellationEventConsumer(cancelMovementsUsecase);
        UUID orderId = UUID.randomUUID();
        UUID cancellationEventId = UUID.randomUUID();

        consumer.onOrderCancelled(new OrderCancelledIntegrationEvent(
                cancellationEventId, orderId, Instant.parse("2026-08-10T02:00:00Z")));

        verify(cancelMovementsUsecase)
                .execute(CancelSourceStockMovementsCommand.afterWarehouseConfirmation(
                        StockOperationSource.primaryOrder(orderId.toString()), cancellationEventId));
    }
}
