package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowAllocationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshot;
import java.util.UUID;

/** Workflow checkpoint for the assigned Inventory stock operation handed to WMS. */
record StockOperationAssignmentCheckpoint(
        OrderFulfillmentWorkflowAllocationState state, StockOperationAssignmentSnapshot assignmentSnapshot) {

    StockOperationAssignmentCheckpoint {
        if (state == null) {
            throw WorkflowFailures.invariantViolation("Stock operation assignment checkpoint stage is required");
        }
        if ((state == OrderFulfillmentWorkflowAllocationState.COMMITTED) != (assignmentSnapshot != null)) {
            throw WorkflowFailures.invariantViolation(
                    "Only an assigned stock operation checkpoint can contain a snapshot");
        }
    }

    static StockOperationAssignmentCheckpoint notRequested() {
        return new StockOperationAssignmentCheckpoint(OrderFulfillmentWorkflowAllocationState.NOT_REQUESTED, null);
    }

    static StockOperationAssignmentCheckpoint waiting() {
        return new StockOperationAssignmentCheckpoint(
                OrderFulfillmentWorkflowAllocationState.WAITING_FOR_COMMITMENT, null);
    }

    StockOperationAssignmentCheckpoint recordAssigned(StockOperationAssignmentSnapshot snapshot) {
        if (snapshot == null) {
            throw WorkflowFailures.invariantViolation("Stock operation assignment snapshot is required");
        }
        if (isCommitted()) {
            if (assignmentSnapshot.equals(snapshot)) {
                return this;
            }
            throw WorkflowFailures.invariantViolation("Workflow received conflicting stock operation assignment facts");
        }
        requireWaiting();
        return new StockOperationAssignmentCheckpoint(OrderFulfillmentWorkflowAllocationState.COMMITTED, snapshot);
    }

    boolean isWaiting() {
        return state == OrderFulfillmentWorkflowAllocationState.WAITING_FOR_COMMITMENT;
    }

    boolean isCommitted() {
        return state == OrderFulfillmentWorkflowAllocationState.COMMITTED;
    }

    StockOperationAssignmentSnapshot requireAssignmentSnapshot() {
        if (!isCommitted()) {
            throw WorkflowFailures.invariantViolation("Assignment wait completed without a stock operation snapshot");
        }
        return assignmentSnapshot;
    }

    UUID stockOperationId() {
        return assignmentSnapshot == null ? null : assignmentSnapshot.stockOperationId();
    }

    private void requireWaiting() {
        if (!isWaiting()) {
            throw WorkflowFailures.invariantViolation("Stock operation assignment checkpoint is not waiting");
        }
    }
}
