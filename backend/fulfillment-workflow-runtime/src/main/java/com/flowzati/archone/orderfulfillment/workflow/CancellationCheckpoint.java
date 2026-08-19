package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowCancellationState;
import java.time.Instant;

/** 純粹收納 Workflow replay 所需的取消狀態；業務轉換仍由 Workflow 主線決定。 */
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

    void accept(CancellationRequest acceptedRequest) {
        state = OrderFulfillmentWorkflowCancellationState.REQUESTED;
        request = acceptedRequest;
    }

    void reject() {
        state = OrderFulfillmentWorkflowCancellationState.REJECTED;
    }

    void markOrderCancelled(Instant occurredAt) {
        cancelledAt = occurredAt;
        state = OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED;
    }
}
