package com.flowzati.archone.orderfulfillment.contract.workflow;

/** Workflow 目前位於哪一段跨系統協調；不鏡像 Activity 的執行細節。 */
public enum OrderFulfillmentWorkflowPhase {
    /** Workflow 尚未進入履約流程；主要供啟動前的 Query 顯示。 */
    NOT_STARTED,

    /** 正在要求配貨，或等待可交給 WMS 的 committed allocation。 */
    ALLOCATION,

    /** 正在呼叫 WMS 冪等建立 Shipment。 */
    WMS_SHIPMENT_CREATION,

    /** WMS Shipment 已建立，等待實際交付承運商；取消命令可中斷此等待。 */
    SHIPMENT_HANDOVER,

    /** 已收到承運商交接事實，正在由 Stock context 完成 outbound movements。 */
    OUTBOUND_COMPLETION,

    /** 出庫 movements 已完成，正在由 Ordering context 記錄整單履約完成。 */
    ORDER_COMPLETION,

    /** 正在取得 WMS 取消決策、等待必要的最終結果，或提交 Ordering cancellation。 */
    CANCELLATION,

    /** Workflow 已產生不可再變動的最終 OrderFulfillmentWorkflowStatus。 */
    FINISHED
}
