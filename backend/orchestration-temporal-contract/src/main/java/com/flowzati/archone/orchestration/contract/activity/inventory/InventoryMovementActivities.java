package com.flowzati.archone.orchestration.contract.activity.inventory;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Inventory Movement capability 執行的 fulfillment Activity contract。 */
@ActivityInterface
public interface InventoryMovementActivities {

    // Allocation 與 Movement 目前由同一個 Inventory worker process 執行。
    String TASK_QUEUE = InventoryAllocationActivities.TASK_QUEUE;

    /**
     * 承運商交接後，完成 outbound movements 與實際庫存扣帳。implementation 以 stock operation／move
     * 的 DONE 狀態防止重複扣庫存與重複發布 completion。
     */
    @ActivityMethod(name = "CompleteOutboundMovements")
    void completeOutboundMovements(CompleteOutboundMovementsActivityInput input);
}
