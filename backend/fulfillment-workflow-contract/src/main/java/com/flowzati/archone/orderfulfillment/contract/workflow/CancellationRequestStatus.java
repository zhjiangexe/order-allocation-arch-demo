package com.flowzati.archone.orderfulfillment.contract.workflow;

/** Workflow 對取消 Update 的立即受理狀態。 */
public enum CancellationRequestStatus {
    ACCEPTED,
    ALREADY_REQUESTED,
    ALREADY_CANCELLED,
    REJECTED
}
