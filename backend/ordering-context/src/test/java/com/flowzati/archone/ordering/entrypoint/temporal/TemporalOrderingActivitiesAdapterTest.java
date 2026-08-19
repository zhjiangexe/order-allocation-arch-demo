package com.flowzati.archone.ordering.entrypoint.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.ordering.application.command.CancelOrderCommand;
import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.exception.OrderFulfillmentConflictException;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalOrderingActivitiesAdapterTest {

    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase =
            mock(RecordOrderFulfillmentUsecase.class);
    private final CancelOrderUsecase cancelOrderUsecase = mock(CancelOrderUsecase.class);
    private final TemporalOrderingActivitiesAdapter activities =
            new TemporalOrderingActivitiesAdapter(recordOrderFulfillmentUsecase, cancelOrderUsecase);

    @Test
    void mapsWorkflowInputsAndCancellationDecisionWithoutLeakingDomainTypes() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");
        CancelOrderCommand cancelCommand = new CancelOrderCommand(requestId, orderId, occurredAt, "customer request");
        when(cancelOrderUsecase.cancel(cancelCommand)).thenReturn(Order.CancellationResult.CANCELLED);

        activities.recordOrderFulfillment(
                new RecordOrderFulfillmentActivityInput("process-1", orderId, shipmentId, occurredAt));
        var result = activities.cancelOrder(
                new CancelOrderActivityInput("process-1", requestId, orderId, occurredAt, "customer request"));

        verify(recordOrderFulfillmentUsecase)
                .execute(new RecordOrderFulfillmentCommand(orderId, shipmentId, occurredAt));
        verify(cancelOrderUsecase).cancel(cancelCommand);
        assertThat(result.status()).isEqualTo(CancelOrderActivityStatus.CANCELLED);
    }

    @Test
    void marksImmutableFulfillmentConflictsAsNonRetryable() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant fulfilledAt = Instant.parse("2026-08-19T10:00:00Z");
        RecordOrderFulfillmentCommand command = new RecordOrderFulfillmentCommand(orderId, shipmentId, fulfilledAt);
        doThrow(new OrderFulfillmentConflictException("different fulfillment"))
                .when(recordOrderFulfillmentUsecase)
                .execute(command);

        assertThatThrownBy(() -> activities.recordOrderFulfillment(
                        new RecordOrderFulfillmentActivityInput("process-1", orderId, shipmentId, fulfilledAt)))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> assertThat(((ApplicationFailure) failure).isNonRetryable())
                        .isTrue());
    }
}
