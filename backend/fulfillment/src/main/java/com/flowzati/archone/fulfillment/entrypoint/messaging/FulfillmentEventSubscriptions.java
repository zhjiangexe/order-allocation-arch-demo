package com.flowzati.archone.fulfillment.entrypoint.messaging;

/**
 * Fulfillment 的 Inbox 訂閱名稱。這些值寫進去重紀錄，永遠不能改。
 *
 * <p>Temporal 模式的三個名稱與其他 context 的訂閱<b>刻意相同</b>：{@code allocation-ordering-events}
 * （inventory allocation）、{@code wms-fulfillment-handoff}（wms）、{@code inventory-shipment-handover}（inventory
 * movement）。那三個 consumer 只在 events 模式啟用，這裡的只在 temporal 模式啟用，兩種模式互斥，同一時間只有一方
 * 使用這個名稱；由哪一方驅動履約流程，就由哪一方承接同一份訂閱。
 */
public final class FulfillmentEventSubscriptions {

    /** events 模式：WMS 回報取消結果。 */
    public static final String CANCELLATION_WMS_RESULTS = "fulfillment-cancellation-wms-results";

    /** events 模式：Ordering 回報取消結果。 */
    public static final String CANCELLATION_ORDERING_RESULTS = "fulfillment-cancellation-ordering-results";

    /** temporal 模式：訂單成立 → 開始履約流程。與 inventory allocation 刻意共用。 */
    public static final String WORKFLOW_ORDER_PLACEMENT = "allocation-ordering-events";

    /** temporal 模式：庫存已配貨。與 wms 刻意共用。 */
    public static final String WORKFLOW_STOCK_OPERATION_ASSIGNED = "wms-fulfillment-handoff";

    /** temporal 模式：出貨單已交給物流商。與 inventory movement 刻意共用。 */
    public static final String WORKFLOW_SHIPMENT_HANDOVER = "inventory-shipment-handover";

    /** temporal 模式：出貨單已取消。 */
    public static final String WORKFLOW_SHIPMENT_CANCELLATION = "temporal-shipment-cancellation";

    private FulfillmentEventSubscriptions() {}
}
