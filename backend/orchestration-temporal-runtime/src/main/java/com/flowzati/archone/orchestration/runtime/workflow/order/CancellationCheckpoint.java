package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import java.time.Instant;
import java.util.UUID;

/** 由單一 Workflow 持有的取消狀態；轉移前完成檢查，已接受的取消請求不會被覆寫。 */
final class CancellationCheckpoint {

    private OrderFulfillmentCancellationState state = OrderFulfillmentCancellationState.NONE;
    private CancellationRequestInput request;
    private Instant cancelledAt;

    OrderFulfillmentCancellationState state() {
        return state;
    }

    CancellationRequestInput request() {
        return request;
    }

    Instant cancelledAt() {
        return cancelledAt;
    }

    /** 是否曾接受取消請求；Order 取消完成後仍為 true。 */
    boolean hasRequest() {
        return state != OrderFulfillmentCancellationState.NONE;
    }

    boolean isOrderCancelled() {
        return state == OrderFulfillmentCancellationState.ORDER_CANCELLED;
    }

    /** 已接受請求但未記錄 Order 取消完成；正常履約勝出時仍可能為 true，不代表最終取消結果。 */
    boolean isRequested() {
        return state == OrderFulfillmentCancellationState.REQUESTED;
    }

    boolean matchesRequest(UUID requestId) {
        return request != null && request.requestId().equals(requestId);
    }

    void validateRepeatedRequest(CancellationRequestInput candidate) {
        if (matchesRequest(candidate.requestId()) && !request.equals(candidate)) {
            throw new IllegalArgumentException("Cancellation request content conflicts with the accepted request");
        }
    }

    UUID requestIdOrNull() {
        return request == null ? null : request.requestId();
    }

    UUID effectiveRequestId(UUID incomingRequestId) {
        return hasRequest() ? request.requestId() : incomingRequestId;
    }

    Instant requestedAtOrNull() {
        return request == null ? null : request.requestedAt();
    }

    void recordRequest(CancellationRequestInput acceptedRequest) {
        if (state != OrderFulfillmentCancellationState.NONE) {
            throw WorkflowFailures.invariantViolation("Cancellation request has already been recorded");
        }
        request = acceptedRequest;
        state = OrderFulfillmentCancellationState.REQUESTED;
    }

    /** 僅在 Ordering 取消 Activity 成功返回且結果未拒絕取消後，記錄 Order 取消完成事實。 */
    void markOrderCancelled(Instant occurredAt) {
        if (state != OrderFulfillmentCancellationState.REQUESTED) {
            throw WorkflowFailures.invariantViolation("Order cancellation requires a requested cancellation");
        }
        cancelledAt = occurredAt;
        state = OrderFulfillmentCancellationState.ORDER_CANCELLED;
    }
}
