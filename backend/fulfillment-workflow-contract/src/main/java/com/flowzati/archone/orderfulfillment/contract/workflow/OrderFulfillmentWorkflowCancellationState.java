package com.flowzati.archone.orderfulfillment.contract.workflow;

/** 取消協調狀態；與履約主線 {@link OrderFulfillmentWorkflowPhase} 分開，避免 cancellation concern 散落成多個欄位。 */
public enum OrderFulfillmentWorkflowCancellationState {
    NONE,
    REQUESTED,
    ORDER_CANCELLED,
    REJECTED
}
