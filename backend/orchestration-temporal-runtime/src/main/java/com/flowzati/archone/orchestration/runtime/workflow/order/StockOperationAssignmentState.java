package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentAllocationState;
import java.util.UUID;

/**
 * Workflow 已觀察到的配貨進度與交給 WMS 的 immutable assignment，與目前執行中的 Workflow phase 分開保存。
 * 已接受的 assignment 不會被後續訊息覆寫，因此主流程可在等待期間持有該事實。
 */
final class StockOperationAssignmentState {

    private OrderFulfillmentAllocationState state = OrderFulfillmentAllocationState.NOT_REQUESTED;
    private StockOperationAssignedInput assignmentSnapshot;

    OrderFulfillmentAllocationState state() {
        return state;
    }

    StockOperationAssignedInput assignmentSnapshot() {
        return assignmentSnapshot;
    }

    /** 僅在 execution 開始配貨時初始化；不是清除既有 assignment 或重新改派的操作。 */
    void markRequested() {
        state = OrderFulfillmentAllocationState.REQUESTED;
    }

    void recordAssigned(StockOperationAssignedInput snapshot) {
        if (isCommitted()) {
            if (assignmentSnapshot.equals(snapshot)) {
                return;
            }
            throw WorkflowFailures.invariantViolation("Workflow received conflicting stock operation assignment facts");
        }
        assignmentSnapshot = snapshot;
        state = OrderFulfillmentAllocationState.COMMITTED;
    }

    boolean isRequested() {
        return state == OrderFulfillmentAllocationState.REQUESTED;
    }

    boolean isCommitted() {
        return state == OrderFulfillmentAllocationState.COMMITTED;
    }

    /** 僅判斷階段是否允許處理訊息；重複與衝突事實仍由 recordAssigned 判斷。 */
    boolean canReceiveAssignment() {
        return isRequested() || isCommitted();
    }

    UUID stockOperationId() {
        return assignmentSnapshot == null ? null : assignmentSnapshot.stockOperationId();
    }
}
