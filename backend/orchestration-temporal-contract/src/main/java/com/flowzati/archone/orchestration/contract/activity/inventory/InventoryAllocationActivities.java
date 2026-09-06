package com.flowzati.archone.orchestration.contract.activity.inventory;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Inventory Allocation capability 執行的 fulfillment Activity contract。 */
@ActivityInterface
public interface InventoryAllocationActivities {

    // 目前 Inventory 與 Ordering 部署在同一 process，先保留既有 task queue 與 Workflow history。
    String TASK_QUEUE = "order-promising-activities";

    /**
     * 要求開始或冪等重送訂單配貨。返回只代表 command 已執行；最終 assigned stock-operation
     * snapshot 仍由 {@code stockOperationAssigned} Signal 回報。implementation 以 Order 的
     * {@code ORDER/orderId/PRIMARY} source unit 為冪等鍵；已指派的 operation 不得再次 reserve 或發布 completion。
     */
    @ActivityMethod(name = "RequestOrderAllocation")
    void requestAllocation(RequestAllocationActivityInput input);
}
