package com.flowzati.archone.orderfulfillment.contract.workflow;

/** 訂單履約 Workflow 所觀察到的配貨進度，不是 Ordering 的 {@code OrderStatus} 複本。 */
public enum OrderFulfillmentWorkflowAllocationState {
    /** Workflow 尚未要求配貨。 */
    NOT_REQUESTED,

    /** 已要求配貨，仍未取得可交給 WMS 的 committed snapshot。 */
    WAITING_FOR_COMMITMENT,

    /** 已取得可交給 WMS 的 committed picking-assignment snapshot。 */
    COMMITTED
}
