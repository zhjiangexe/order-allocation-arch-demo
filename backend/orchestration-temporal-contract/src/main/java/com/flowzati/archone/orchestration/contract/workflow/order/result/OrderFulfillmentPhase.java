package com.flowzati.archone.orchestration.contract.workflow.order.result;

/** Workflow 目前位於哪一段跨系統協調；不鏡像 Activity 的執行細節。 */
public enum OrderFulfillmentPhase {
    /** Workflow 尚未進入履約流程；主要供啟動前的 Query 顯示。 */
    NOT_STARTED,

    /** 正在要求配貨，或等待可交給 WMS 的 committed stock-operation assignment。 */
    ALLOCATION,

    /** 正在向 WMS 下達出庫需求並確認 Shipment 身分；不是 WMS 內部的 Wave Release。 */
    WAREHOUSE_RELEASE,

    /** Shipment 身分已確認，等待倉內作業與承運商交接結果；取消請求可中斷此等待。 */
    WAREHOUSE_EXECUTION,

    /** 已確認承運商交接事實，正在由 Inventory 完成庫存扣減與 movement／operation 紀錄。 */
    INVENTORY_FINALIZATION,

    /** 出庫 movements 已完成，正在由 Ordering context 記錄整單履約完成。 */
    ORDER_COMPLETION,

    /** 正在取得 WMS 取消決策、等待必要的最終結果，或提交 Ordering cancellation。 */
    CANCELLING,

    /** Workflow 已產生不可再變動的最終 OrderFulfillmentOutcome。 */
    FINISHED
}
