package com.flowzati.archone.orderfulfillment.contract.workflow;

/** 只有 Workflow 結束時才存在的結果；進行中的 {@link OrderFulfillmentWorkflowSnapshot#outcome()} 為 {@code null}。 */
public enum OrderFulfillmentWorkflowStatus {
    /** Shipment 已交付承運商，outbound movements 與 Order 終態皆已完成。 */
    FULFILLMENT_COMPLETED,

    /**
     * WMS 已安全停止／復原（若 Shipment 存在），且 Ordering 已提交取消。
     * Stock movement 的釋放仍可由 OrderCancelled Outbox event 非同步完成。
     */
    ORDER_CANCELLED
}
