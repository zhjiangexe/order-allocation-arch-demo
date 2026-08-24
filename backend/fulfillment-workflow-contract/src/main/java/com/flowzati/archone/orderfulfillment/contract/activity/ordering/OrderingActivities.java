package com.flowzati.archone.orderfulfillment.contract.activity.ordering;

import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Ordering context 執行的 fulfillment Activity contract。 */
@ActivityInterface
public interface OrderingActivities {

    // 目前 Inventory 與 Ordering 部署在同一 process，先保留既有 task queue 與 Workflow history。
    String TASK_QUEUE = InventoryActivities.TASK_QUEUE;

    /**
     * 出庫 movements 完成後，將整張訂單冪等推進到 FULFILLED；相同 Shipment ID 與完成時間為 no-op，
     * 不同 immutable fact 必須拒絕。
     */
    @ActivityMethod(name = "RecordOrderFulfillment")
    void recordOrderFulfillment(RecordOrderFulfillmentActivityInput input);

    /**
     * 執行 Ordering 的冪等取消 transaction。同一 request ID、取消時間與原因可安全重播，不同 immutable
     * request 必須拒絕。業務結果使用 typed result；技術性失敗交由 Temporal retry。
     */
    @ActivityMethod(name = "CancelOrder")
    CancelOrderActivityResult cancelOrder(CancelOrderActivityInput input);
}
