package com.flowzati.archone.orchestration.contract.workflow.order.result;

/** 訂單履約 Workflow 所觀察到的配貨進度，不是 Ordering 的 {@code OrderStatus} 複本。 */
public enum OrderFulfillmentAllocationState {
    /** Workflow 尚未要求配貨。 */
    NOT_REQUESTED,

    /**
     * Workflow 已發起配貨，但尚未接受 committed assignment；不保證配貨 Activity 已成功返回。
     * 這是已發起請求的里程碑，提前取消結束後仍可保留，不代表主流程仍在等待。
     */
    REQUESTED,

    /** 已取得可交給 WMS 的 committed stock-operation assignment snapshot。 */
    COMMITTED
}
