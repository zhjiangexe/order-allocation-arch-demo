package com.flowzati.archone.ordering.entrypoint.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemporalOrderActivitiesAdapterTest {

    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase =
            mock(RecordOrderFulfillmentUsecase.class);
    private final CancelOrderUsecase cancelOrderUsecase = mock(CancelOrderUsecase.class);
    private final TemporalOrderActivitiesAdapter activities =
            new TemporalOrderActivitiesAdapter(recordOrderFulfillmentUsecase, cancelOrderUsecase);

    @ParameterizedTest
    @CsvSource({"CANCELLED,CANCELLED", "ALREADY_CANCELLED,ALREADY_CANCELLED", "REJECTED,REJECTED"})
    void mapsWorkflowInputsAndCancellationDecisionWithoutLeakingDomainTypes(
            Order.CancellationStatus domainStatus, CancelOrderActivityStatus expectedStatus) {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");
        CancelOrderCommand cancelCommand = new CancelOrderCommand(requestId, orderId, occurredAt, "customer request");
        when(cancelOrderUsecase.cancel(cancelCommand)).thenReturn(domainStatus);

        activities.recordOrderFulfillment(
                new RecordOrderFulfillmentActivityInput("process-1", orderId, shipmentId, occurredAt));
        var result = activities.cancelOrder(
                new CancelOrderActivityInput("process-1", requestId, orderId, occurredAt, "customer request"));

        verify(recordOrderFulfillmentUsecase)
                .execute(new RecordOrderFulfillmentCommand(orderId, shipmentId, occurredAt));
        verify(cancelOrderUsecase).cancel(cancelCommand);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.status()).isEqualTo(expectedStatus);
    }

    @Test
    void marksImmutableFulfillmentConflictsAsNonRetryable() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant fulfilledAt = Instant.parse("2026-08-19T10:00:00Z");
        RecordOrderFulfillmentCommand command = new RecordOrderFulfillmentCommand(orderId, shipmentId, fulfilledAt);
        doThrow(new DomainConflictException(OrderErrorCode.FULFILLMENT_CONFLICT, "different fulfillment"))
                .when(recordOrderFulfillmentUsecase)
                .execute(command);

        assertThatThrownBy(() -> activities.recordOrderFulfillment(
                        new RecordOrderFulfillmentActivityInput("process-1", orderId, shipmentId, fulfilledAt)))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getType()).isEqualTo(OrderErrorCode.FULFILLMENT_CONFLICT.value());
                });
    }

    @Test
    void marksCancellationRequestConflictsAsNonRetryable() {
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant cancelledAt = Instant.parse("2026-08-19T10:00:00Z");
        CancelOrderCommand command = new CancelOrderCommand(requestId, orderId, cancelledAt, "customer request");
        when(cancelOrderUsecase.cancel(command))
                .thenThrow(new DomainConflictException(
                        OrderErrorCode.CANCELLATION_REQUEST_CONFLICT, "different cancellation request"));

        assertThatThrownBy(() -> activities.cancelOrder(
                        new CancelOrderActivityInput("process-1", requestId, orderId, cancelledAt, "customer request")))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getType()).isEqualTo(OrderErrorCode.CANCELLATION_REQUEST_CONFLICT.value());
                });
    }
}
