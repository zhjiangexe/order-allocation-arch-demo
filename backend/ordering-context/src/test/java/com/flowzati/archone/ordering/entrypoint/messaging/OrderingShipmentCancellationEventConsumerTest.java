package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingShipmentCancellationEventConsumerTest {

    private final CancelOrderUsecase cancelOrderUsecase = mock(CancelOrderUsecase.class);
    private final OrderingShipmentCancellationEventConsumer consumer =
            new OrderingShipmentCancellationEventConsumer(cancelOrderUsecase);

    @Test
    void cancelsOrderAtTheActualShipmentCancellationTime() {
        ShipmentCancelledIntegrationEvent event = event();
        when(cancelOrderUsecase.cancel(any())).thenReturn(Order.CancellationStatus.CANCELLED);
        consumer.onShipmentCancelled(event);

        verify(cancelOrderUsecase)
                .cancel(new CancelOrderCommand(
                        event.getCancellationRequestId(),
                        event.getOrderId(),
                        event.getCancelledAt(),
                        event.getCancellationReason()));
    }

    @Test
    void rejectsContradictoryOrderingResultAfterWmsCancellation() {
        when(cancelOrderUsecase.cancel(any())).thenReturn(Order.CancellationStatus.REJECTED);
        assertThatThrownBy(() -> consumer.onShipmentCancelled(event()))
                .isInstanceOf(DomainConflictException.class)
                .hasMessageContaining("Ordering rejected cancellation after WMS cancelled Shipment");
    }

    private static ShipmentCancelledIntegrationEvent event() {
        Instant requestedAt = Instant.parse("2026-08-24T10:00:00Z");
        return new ShipmentCancelledIntegrationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                requestedAt,
                "customer request",
                requestedAt.plusSeconds(30));
    }
}
