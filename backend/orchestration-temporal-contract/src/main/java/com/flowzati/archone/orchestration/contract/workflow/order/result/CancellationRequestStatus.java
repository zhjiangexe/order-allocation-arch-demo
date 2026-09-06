package com.flowzati.archone.orchestration.contract.workflow.order.result;

/** Workflow 對取消 Update 的立即受理狀態。 */
public enum CancellationRequestStatus {
    ACCEPTED,
    ALREADY_REQUESTED,
    ALREADY_CANCELLED,
    REJECTED
}
