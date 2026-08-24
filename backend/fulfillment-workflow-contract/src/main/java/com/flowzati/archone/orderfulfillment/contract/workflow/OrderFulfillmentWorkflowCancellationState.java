package com.flowzati.archone.orderfulfillment.contract.workflow;

/** 已達成的取消協調里程碑；REQUESTED 可與最終正常履約並存，履約路線另由 Workflow outcome 表示。 */
public enum OrderFulfillmentWorkflowCancellationState {
    NONE,
    REQUESTED,
    ORDER_CANCELLED
}
