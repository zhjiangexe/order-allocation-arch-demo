package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancellationStateTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T10:00:00Z");
    private static final CancellationRequestInput REQUEST =
            new CancellationRequestInput(UUID.randomUUID(), UUID.randomUUID(), REQUESTED_AT, "Customer request");

    @Test
    void retainsTheRequestAfterRejectionWithoutTreatingItAsPendingOrCancelled() {
        CancellationState state = new CancellationState();
        state.recordRequest(REQUEST);

        state.markRejected();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.REJECTED);
        assertThat(state.hasRequest()).isTrue();
        assertThat(state.isRequested()).isFalse();
        assertThat(state.isRejected()).isTrue();
        assertThat(state.isOrderCancelled()).isFalse();
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isNull();
    }

    @Test
    void distinguishesAnAcceptedRequestFromPendingAndCompletedCancellation() {
        CancellationState state = new CancellationState();

        assertThat(state.hasRequest()).isFalse();
        assertThat(state.isRequested()).isFalse();
        assertThat(state.isOrderCancelled()).isFalse();

        state.recordRequest(REQUEST);

        assertThat(state.hasRequest()).isTrue();
        assertThat(state.isRequested()).isTrue();
        assertThat(state.isOrderCancelled()).isFalse();

        state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(state.hasRequest()).isTrue();
        assertThat(state.isRequested()).isFalse();
        assertThat(state.isOrderCancelled()).isTrue();
    }

    @Test
    void usesTheIncomingRequestIdOnlyUntilARequestIsAccepted() {
        CancellationState state = new CancellationState();
        UUID incomingRequestId = UUID.randomUUID();

        assertThat(state.effectiveRequestId(incomingRequestId)).isEqualTo(incomingRequestId);
        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(state.request()).isNull();

        state.recordRequest(REQUEST);

        assertThat(state.effectiveRequestId(incomingRequestId)).isEqualTo(REQUEST.requestId());
        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(state.request()).isSameAs(REQUEST);

        state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(state.effectiveRequestId(incomingRequestId)).isEqualTo(REQUEST.requestId());
        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void updatesTheSameStateForEachTransition() {
        CancellationState state = new CancellationState();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(state.request()).isNull();
        assertThat(state.cancelledAt()).isNull();

        state.recordRequest(REQUEST);

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isNull();

        state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void validatesTheFirstRequestWithoutRecordingIt() {
        CancellationState state = new CancellationState();

        assertThat(state.conflictsWith(REQUEST)).isFalse();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(state.request()).isNull();
        assertThat(state.cancelledAt()).isNull();
    }

    @Test
    void acceptsIdenticalRequestContentBeforeAndAfterCancellation() {
        CancellationState state = new CancellationState();
        state.recordRequest(REQUEST);
        CancellationRequestInput replay = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt(), REQUEST.reason());

        assertThat(state.conflictsWith(replay)).isFalse();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isNull();

        state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));
        assertThat(state.conflictsWith(replay)).isFalse();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void rejectsChangedContentForTheAcceptedRequestId() {
        CancellationState state = new CancellationState();
        state.recordRequest(REQUEST);
        CancellationRequestInput changedReason = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt(), "Different reason");
        CancellationRequestInput changedTime = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt().plusSeconds(1), REQUEST.reason());

        for (boolean orderCancelled : List.of(false, true)) {
            if (orderCancelled) {
                state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));
            }
            OrderFulfillmentCancellationState originalState = state.state();
            Instant originalCancelledAt = state.cancelledAt();
            for (CancellationRequestInput conflicting : List.of(changedReason, changedTime)) {
                assertThat(state.conflictsWith(conflicting)).isTrue();
                assertThat(state.state()).isEqualTo(originalState);
                assertThat(state.request()).isSameAs(REQUEST);
                assertThat(state.cancelledAt()).isEqualTo(originalCancelledAt);
            }
        }
    }

    @Test
    void leavesDifferentRequestIdsToTheWorkflowWithoutReplacingTheAcceptedRequest() {
        CancellationState state = new CancellationState();
        state.recordRequest(REQUEST);
        CancellationRequestInput anotherRequest = new CancellationRequestInput(
                UUID.randomUUID(), REQUEST.orderId(), REQUESTED_AT.plusSeconds(1), "Another request");

        assertThat(state.conflictsWith(anotherRequest)).isFalse();

        assertThat(state.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(state.request()).isSameAs(REQUEST);
        assertThat(state.cancelledAt()).isNull();
    }

    @Test
    void matchesOnlyTheAcceptedRequestIdBeforeAndAfterCancellation() {
        CancellationState state = new CancellationState();
        UUID anotherRequestId = UUID.randomUUID();

        assertThat(state.matchesRequest(REQUEST.requestId())).isFalse();

        state.recordRequest(REQUEST);

        assertThat(state.matchesRequest(REQUEST.requestId())).isTrue();
        assertThat(state.matchesRequest(anotherRequestId)).isFalse();

        state.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(state.matchesRequest(REQUEST.requestId())).isTrue();
        assertThat(state.matchesRequest(anotherRequestId)).isFalse();
    }
}
