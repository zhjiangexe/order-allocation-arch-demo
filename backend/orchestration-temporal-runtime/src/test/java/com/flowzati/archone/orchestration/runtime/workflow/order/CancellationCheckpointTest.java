package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancellationCheckpointTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T10:00:00Z");
    private static final CancellationRequestInput REQUEST =
            new CancellationRequestInput(UUID.randomUUID(), UUID.randomUUID(), REQUESTED_AT, "Customer request");

    @Test
    void retainsTheRequestAfterRejectionWithoutTreatingItAsPendingOrCancelled() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);

        checkpoint.markRejected();

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REJECTED);
        assertThat(checkpoint.hasRequest()).isTrue();
        assertThat(checkpoint.isRequested()).isFalse();
        assertThat(checkpoint.isRejected()).isTrue();
        assertThat(checkpoint.isOrderCancelled()).isFalse();
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isNull();
        assertThatThrownBy(() -> checkpoint.markOrderCancelled(REQUESTED_AT)).isInstanceOf(ApplicationFailure.class);
    }

    @Test
    void cannotRejectCancellationWithoutARequest() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();

        assertThatThrownBy(checkpoint::markRejected).isInstanceOf(ApplicationFailure.class);
        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
    }

    @Test
    void distinguishesAnAcceptedRequestFromPendingAndCompletedCancellation() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();

        assertThat(checkpoint.hasRequest()).isFalse();
        assertThat(checkpoint.isRequested()).isFalse();
        assertThat(checkpoint.isOrderCancelled()).isFalse();

        checkpoint.recordRequest(REQUEST);

        assertThat(checkpoint.hasRequest()).isTrue();
        assertThat(checkpoint.isRequested()).isTrue();
        assertThat(checkpoint.isOrderCancelled()).isFalse();

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(checkpoint.hasRequest()).isTrue();
        assertThat(checkpoint.isRequested()).isFalse();
        assertThat(checkpoint.isOrderCancelled()).isTrue();
    }

    @Test
    void usesTheIncomingRequestIdOnlyUntilARequestIsAccepted() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        UUID incomingRequestId = UUID.randomUUID();

        assertThat(checkpoint.effectiveRequestId(incomingRequestId)).isEqualTo(incomingRequestId);
        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(checkpoint.request()).isNull();

        checkpoint.recordRequest(REQUEST);

        assertThat(checkpoint.effectiveRequestId(incomingRequestId)).isEqualTo(REQUEST.requestId());
        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(checkpoint.effectiveRequestId(incomingRequestId)).isEqualTo(REQUEST.requestId());
        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void updatesTheSameCheckpointForEachTransition() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(checkpoint.request()).isNull();
        assertThat(checkpoint.cancelledAt()).isNull();

        checkpoint.recordRequest(REQUEST);

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isNull();

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void rejectsOrderCancellationBeforeARequestExistsWithoutChangingState() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();

        assertThatThrownBy(() -> checkpoint.markOrderCancelled(REQUESTED_AT))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("requires a requested cancellation");

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(checkpoint.request()).isNull();
        assertThat(checkpoint.cancelledAt()).isNull();
    }

    @Test
    void rejectsRecordingAnotherRequestWithoutChangingState() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);
        CancellationRequestInput anotherRequest = new CancellationRequestInput(
                UUID.randomUUID(), REQUEST.orderId(), REQUESTED_AT.plusSeconds(1), "Another request");

        assertThatThrownBy(() -> checkpoint.recordRequest(anotherRequest))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("already been recorded");

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isNull();

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(2));

        assertThatThrownBy(() -> checkpoint.recordRequest(anotherRequest))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("already been recorded");

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(2));
    }

    @Test
    void rejectsRepeatedOrderCancellationWithoutReplacingTheCompletionTime() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);
        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(2)))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("requires a requested cancellation");

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void validatesTheFirstRequestWithoutRecordingIt() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();

        checkpoint.validateRepeatedRequest(REQUEST);

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.NONE);
        assertThat(checkpoint.request()).isNull();
        assertThat(checkpoint.cancelledAt()).isNull();
    }

    @Test
    void acceptsIdenticalRequestContentBeforeAndAfterCancellation() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);
        CancellationRequestInput replay = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt(), REQUEST.reason());

        checkpoint.validateRepeatedRequest(replay);

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isNull();

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));
        checkpoint.validateRepeatedRequest(replay);

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void rejectsChangedContentForTheAcceptedRequestId() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);
        CancellationRequestInput changedReason = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt(), "Different reason");
        CancellationRequestInput changedTime = new CancellationRequestInput(
                REQUEST.requestId(), REQUEST.orderId(), REQUEST.requestedAt().plusSeconds(1), REQUEST.reason());

        for (boolean orderCancelled : List.of(false, true)) {
            if (orderCancelled) {
                checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));
            }
            OrderFulfillmentCancellationState originalState = checkpoint.state();
            Instant originalCancelledAt = checkpoint.cancelledAt();
            for (CancellationRequestInput conflicting : List.of(changedReason, changedTime)) {
                assertThatThrownBy(() -> checkpoint.validateRepeatedRequest(conflicting))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Cancellation request content conflicts with the accepted request");
                assertThat(checkpoint.state()).isEqualTo(originalState);
                assertThat(checkpoint.request()).isSameAs(REQUEST);
                assertThat(checkpoint.cancelledAt()).isEqualTo(originalCancelledAt);
            }
        }
    }

    @Test
    void leavesDifferentRequestIdsToTheWorkflowWithoutReplacingTheAcceptedRequest() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        checkpoint.recordRequest(REQUEST);
        CancellationRequestInput anotherRequest = new CancellationRequestInput(
                UUID.randomUUID(), REQUEST.orderId(), REQUESTED_AT.plusSeconds(1), "Another request");

        checkpoint.validateRepeatedRequest(anotherRequest);

        assertThat(checkpoint.state()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(checkpoint.request()).isSameAs(REQUEST);
        assertThat(checkpoint.cancelledAt()).isNull();
    }

    @Test
    void matchesOnlyTheAcceptedRequestIdBeforeAndAfterCancellation() {
        CancellationCheckpoint checkpoint = new CancellationCheckpoint();
        UUID anotherRequestId = UUID.randomUUID();

        assertThat(checkpoint.matchesRequest(REQUEST.requestId())).isFalse();

        checkpoint.recordRequest(REQUEST);

        assertThat(checkpoint.matchesRequest(REQUEST.requestId())).isTrue();
        assertThat(checkpoint.matchesRequest(anotherRequestId)).isFalse();

        checkpoint.markOrderCancelled(REQUESTED_AT.plusSeconds(1));

        assertThat(checkpoint.matchesRequest(REQUEST.requestId())).isTrue();
        assertThat(checkpoint.matchesRequest(anotherRequestId)).isFalse();
    }
}
