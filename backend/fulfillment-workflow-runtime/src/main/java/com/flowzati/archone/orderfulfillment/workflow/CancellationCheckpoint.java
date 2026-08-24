package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowCancellationState;
import java.time.Instant;
import java.util.UUID;

/** 收納 Workflow replay 所需的取消請求事實；Shipment terminal fact 才決定正常履約或取消路線。 */
final class CancellationCheckpoint {

    private OrderFulfillmentWorkflowCancellationState state = OrderFulfillmentWorkflowCancellationState.NONE;
    private CancellationRequest request;
    private Instant cancelledAt;

    OrderFulfillmentWorkflowCancellationState state() {
        return state;
    }

    CancellationRequest request() {
        return request;
    }

    Instant cancelledAt() {
        return cancelledAt;
    }

    boolean isRequested() {
        return state == OrderFulfillmentWorkflowCancellationState.REQUESTED;
    }

    CancellationRequest requireRequest() {
        if (request == null) {
            throw WorkflowFailures.invariantViolation("Cancellation state " + state + " requires an accepted request");
        }
        return request;
    }

    UUID requestIdOrNull() {
        return request == null ? null : request.requestId();
    }

    Instant requestedAtOrNull() {
        return request == null ? null : request.requestedAt();
    }

    void recordRequest(CancellationRequest acceptedRequest) {
        state = OrderFulfillmentWorkflowCancellationState.REQUESTED;
        request = acceptedRequest;
    }

    void markOrderCancelled(Instant occurredAt) {
        cancelledAt = occurredAt;
        state = OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED;
    }
}
