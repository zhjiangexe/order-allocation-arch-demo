package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowAllocationState;
import java.util.UUID;

/**
 * Workflow 對配貨邊界的最小認知，不是 {@code OrderStatus} 的複本。
 *
 * <p>{@code state} 只回答「是否已拿到可交給 WMS 的 committed snapshot」。補貨等待與
 * BACKORDERED 歷程留在 Order／Inventory 的 domain model 與 read model。
 */
record AllocationCheckpoint(OrderFulfillmentWorkflowAllocationState state, AllocationSnapshot committedSnapshot) {

    AllocationCheckpoint {
        if (state == null) {
            throw WorkflowFailures.invariantViolation("Allocation checkpoint stage is required");
        }
        if ((state == OrderFulfillmentWorkflowAllocationState.COMMITTED) != (committedSnapshot != null)) {
            throw WorkflowFailures.invariantViolation("Only a committed allocation checkpoint can contain a snapshot");
        }
    }

    static AllocationCheckpoint notRequested() {
        return new AllocationCheckpoint(OrderFulfillmentWorkflowAllocationState.NOT_REQUESTED, null);
    }

    static AllocationCheckpoint waiting() {
        return new AllocationCheckpoint(OrderFulfillmentWorkflowAllocationState.WAITING_FOR_COMMITMENT, null);
    }

    AllocationCheckpoint committed(AllocationSnapshot snapshot) {
        requireWaiting();
        if (snapshot == null) {
            throw WorkflowFailures.invariantViolation("Committed allocation snapshot is required");
        }
        return new AllocationCheckpoint(OrderFulfillmentWorkflowAllocationState.COMMITTED, snapshot);
    }

    boolean isWaiting() {
        return state == OrderFulfillmentWorkflowAllocationState.WAITING_FOR_COMMITMENT;
    }

    boolean isCommitted() {
        return state == OrderFulfillmentWorkflowAllocationState.COMMITTED;
    }

    AllocationSnapshot requireCommittedSnapshot() {
        if (!isCommitted()) {
            throw WorkflowFailures.invariantViolation("Allocation wait completed without a committed snapshot");
        }
        return committedSnapshot;
    }

    UUID allocationId() {
        return committedSnapshot == null ? null : committedSnapshot.allocationId();
    }

    private void requireWaiting() {
        if (!isWaiting()) {
            throw WorkflowFailures.invariantViolation("Allocation checkpoint is not waiting for commitment");
        }
    }
}
