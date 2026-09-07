package com.flowzati.archone.ordering.entrypoint.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.time.Instant;
import java.util.UUID;
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
}
