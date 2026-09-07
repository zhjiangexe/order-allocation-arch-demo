package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import java.time.Instant;
import java.util.UUID;

/** Workflow 已觀察到的取消請求與處理結果；由 Workflow 協調狀態轉移。 */
final class CancellationState {

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

    boolean isRejected() {
        return state == OrderFulfillmentCancellationState.REJECTED;
    }

    void markRejected() {
        state = OrderFulfillmentCancellationState.REJECTED;
    }

    /** 已接受請求但未記錄 Order 取消完成；正常履約勝出時仍可能為 true，不代表最終取消結果。 */
    boolean isRequested() {
        return state == OrderFulfillmentCancellationState.REQUESTED;
    }

    boolean matchesRequest(UUID requestId) {
        return request != null && request.requestId().equals(requestId);
    }

    boolean conflictsWith(CancellationRequestInput candidate) {
        return matchesRequest(candidate.requestId()) && !request.equals(candidate);
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
        request = acceptedRequest;
        state = OrderFulfillmentCancellationState.REQUESTED;
    }

    /** 僅在 Ordering 取消 Activity 成功返回且結果未拒絕取消後，記錄 Order 取消完成事實。 */
    void markOrderCancelled(Instant occurredAt) {
        cancelledAt = occurredAt;
        state = OrderFulfillmentCancellationState.ORDER_CANCELLED;
    }
}
