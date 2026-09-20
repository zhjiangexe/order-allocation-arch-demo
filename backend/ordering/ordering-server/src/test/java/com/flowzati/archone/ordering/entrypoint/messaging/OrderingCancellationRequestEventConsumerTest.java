package com.flowzati.archone.ordering.entrypoint.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.ordering.application.event.OrderCancellationResolved;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.port.OrderCancellationResolvedPublisher;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.CompleteOrderCancellationRequestUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingCancellationRequestEventConsumerTest {
    @Test
    void publishesResolutionAfterOrderCancellation() {
        CancelOrderUsecase cancel = mock(CancelOrderUsecase.class);
        OrderCancellationResolvedPublisher publisher = mock(OrderCancellationResolvedPublisher.class);
        UUID requestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        CancelOrderCommand command = new CancelOrderCommand(requestId, orderId, Instant.now(), "customer request");
        org.mockito.Mockito.when(cancel.cancel(command)).thenReturn(Order.CancellationStatus.CANCELLED);

        new CompleteOrderCancellationRequestUsecase(cancel, publisher).complete(command);

        verify(publisher)
                .publish(new OrderCancellationResolved(requestId, orderId, Order.CancellationStatus.CANCELLED));
    }
}
