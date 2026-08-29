package com.flowzati.archone.orderfulfillment.contract.activity.inventory;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Inventory context 執行的 fulfillment Activity contract。 */
@ActivityInterface
public interface InventoryActivities {

    // 目前 Inventory 與 Ordering 部署在同一 process，先保留既有 task queue 與 Workflow history。
    String TASK_QUEUE = "order-promising-activities";

    /**
     * 要求開始或冪等重送訂單配貨。返回只代表 command 已執行；最終 assigned picking
     * snapshot 仍由 {@code pickingAssigned} Signal 回報。implementation 以 Order 的
     * {@code ORDER/orderId/PRIMARY} source unit 為冪等鍵；已指派的 picking 不得再次 reserve 或發布 completion。
     */
    @ActivityMethod(name = "RequestOrderAllocation")
    void requestAllocation(RequestAllocationActivityInput input);

    /**
     * 承運商交接後，完成 outbound movements 與實際庫存扣帳。implementation 以 picking/move
     * 的 DONE 狀態防止重複扣庫存與重複發布 completion。
     */
    @ActivityMethod(name = "CompleteOutboundMovements")
    void completeOutboundMovements(CompleteOutboundMovementsActivityInput input);
}
