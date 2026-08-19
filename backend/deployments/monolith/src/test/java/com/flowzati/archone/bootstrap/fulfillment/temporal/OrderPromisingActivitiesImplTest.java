package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities;
import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderPromisingActivitiesImplTest {

    private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase =
            mock(CompleteOutboundMovementsUsecase.class);
    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase =
            mock(RecordOrderFulfillmentUsecase.class);
    private final CancelOrderUsecase cancelOrderUsecase = mock(CancelOrderUsecase.class);

    private OrderPromisingActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        activities = new OrderPromisingActivitiesImpl(
                allocateOrderUsecase,
                completeOutboundMovementsUsecase,
                recordOrderFulfillmentUsecase,
                cancelOrderUsecase);
    }

    @Test
    void mapsWorkflowInputsToSharedApplicationCommands() {
        UUID orderId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");

        activities.requestAllocation(new OrderPromisingActivities.RequestAllocation("process-1", orderId, occurredAt));
        activities.completeOutboundMovements(new OrderPromisingActivities.CompleteOutboundMovements(
                "process-1", orderId, allocationId, shipmentId, List.of(movementId), occurredAt));
        activities.recordOrderFulfillment(
                new OrderPromisingActivities.RecordOrderFulfillment("process-1", orderId, shipmentId, occurredAt));

        verify(allocateOrderUsecase).execute(new AllocateOrderCommand(orderId));
        verify(completeOutboundMovementsUsecase)
                .execute(new CompleteOutboundMovementsCommand(
                        allocationId, orderId, shipmentId, List.of(movementId), occurredAt));
        verify(recordOrderFulfillmentUsecase).execute(new RecordOrderFulfillmentCommand(orderId, occurredAt));
    }

    @Test
    void mapsOrderingCancellationDecisionWithoutLeakingTheDomainEnum() {
        UUID orderId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(cancelOrderUsecase.cancel(orderId, requestedAt)).thenReturn(Order.CancellationResult.CANCELLED);

        var result = activities.cancelOrder(new OrderPromisingActivities.CancelOrder(
                "process-1", UUID.randomUUID(), orderId, requestedAt, "customer request"));

        assertThat(result.status()).isEqualTo(OrderPromisingActivities.CancelOrderStatus.CANCELLED);
    }
}
