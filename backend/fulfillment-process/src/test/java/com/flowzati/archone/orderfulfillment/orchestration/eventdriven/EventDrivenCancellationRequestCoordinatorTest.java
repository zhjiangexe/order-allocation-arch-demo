package com.flowzati.archone.orderfulfillment.orchestration.eventdriven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.orderfulfillment.application.usecase.AcceptCancellationRequestUsecase;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventDrivenCancellationRequestCoordinatorTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    private final OrderCancellationApi ordering = mock(OrderCancellationApi.class);
    private final AcceptCancellationRequestUsecase accept = mock(AcceptCancellationRequestUsecase.class);
    private final EventDrivenCancellationRequestCoordinator coordinator =
            new EventDrivenCancellationRequestCoordinator(ordering, accept);

    @Test
    void acceptedRequestOnlyPublishesIntentAndNeverCommitsOrderSynchronously() {
        when(ordering.assess(any())).thenReturn(OrderCancellationAssessment.CANCELLABLE);
        when(accept.accept(any())).thenReturn(FulfillmentCancellationStatus.ACCEPTED);

        assertThat(coordinator.request(request()).status()).isEqualTo(FulfillmentCancellationStatus.ACCEPTED);
        verify(accept).accept(request());
        verify(ordering).assess(any());
    }

    @Test
    void alreadyCancelledRequestSkipsPublication() {
        when(ordering.assess(any())).thenReturn(OrderCancellationAssessment.ALREADY_CANCELLED);

        assertThat(coordinator.request(request()).status()).isEqualTo(FulfillmentCancellationStatus.ALREADY_CANCELLED);
        verifyNoInteractions(accept);
    }

    @Test
    void fulfilledOrderIsRejectedBeforePublication() {
        when(ordering.assess(any())).thenReturn(OrderCancellationAssessment.FULFILLED);

        assertThat(coordinator.request(request()).status()).isEqualTo(FulfillmentCancellationStatus.REJECTED);
        verifyNoInteractions(accept);
    }

    private static FulfillmentCancellationCommand request() {
        return new FulfillmentCancellationCommand(REQUEST_ID, ORDER_ID, REQUESTED_AT, "Customer changed mind");
    }
}
