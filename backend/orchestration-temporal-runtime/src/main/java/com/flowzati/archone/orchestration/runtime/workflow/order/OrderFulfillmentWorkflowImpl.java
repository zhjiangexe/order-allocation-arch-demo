package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.RequestAllocationActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.ordering.OrderActivities;
import com.flowzati.archone.orchestration.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityResult;
import com.flowzati.archone.orchestration.contract.activity.wms.ShipmentActivities;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.time.Instant;
import java.util.UUID;

/**
 * Coordinates coarse-grained fulfillment across Inventory, Ordering, and WMS.
 *
 * <p>Temporal retries retryable Activity failures; non-retryable failures remain visible as Workflow failures.
 * Cancellation Updates only record intent. The main flow must wait for an in-flight Activity to return successfully
 * before coordinating cancellation; a pending response does not imply that the external transaction has not committed.
 * Internal Pick, Pack, and Stage operations are not mirrored here. Signals carry facts produced later by people,
 * equipment, or external systems.
 *
 * <p>Each execution supports one assigned stock operation and one Shipment. Split shipments or warehouse reassignment
 * require an explicit fulfillment attempt or multi-Shipment completion policy, rather than overwriting stored state.
 */
public final class OrderFulfillmentWorkflowImpl implements OrderFulfillmentWorkflow {

    private static final String ORDER_CANCELLATION_REJECTED = "ORDER_CANCELLATION_REJECTED";

    private final InventoryAllocationActivities inventoryAllocationActivities = Workflow.newActivityStub(
            InventoryAllocationActivities.class,
            FulfillmentActivityOptions.forTaskQueue(InventoryAllocationActivities.TASK_QUEUE));
    private final InventoryMovementActivities inventoryMovementActivities = Workflow.newActivityStub(
            InventoryMovementActivities.class,
            FulfillmentActivityOptions.forTaskQueue(InventoryMovementActivities.TASK_QUEUE));
    private final OrderActivities orderActivities = Workflow.newActivityStub(
            OrderActivities.class, FulfillmentActivityOptions.forTaskQueue(OrderActivities.TASK_QUEUE));
    private final ShipmentActivities shipmentActivities = Workflow.newActivityStub(
            ShipmentActivities.class, FulfillmentActivityOptions.forTaskQueue(ShipmentActivities.TASK_QUEUE));

    private final OrderFulfillmentInput workflowInput;
    private final WorkflowProgress progress;
    private final StockOperationAssignmentCheckpoint assignmentCheckpoint;
    private final ShipmentCheckpoint shipmentCheckpoint;
    private final CancellationCheckpoint cancellationCheckpoint;

    /**
     * Initializes identity and query state before any Workflow method or Signal handler runs.
     */
    @WorkflowInit
    public OrderFulfillmentWorkflowImpl(OrderFulfillmentInput input) {
        this.workflowInput = input;
        this.assignmentCheckpoint = new StockOperationAssignmentCheckpoint();
        this.shipmentCheckpoint = new ShipmentCheckpoint();
        this.cancellationCheckpoint = new CancellationCheckpoint();
        this.progress = new WorkflowProgress();
    }

    @Override
    public void execute(OrderFulfillmentInput input) {
        String processId = Workflow.getInfo().getWorkflowId();
        assignmentCheckpoint.markRequested();

        // Phase 1 - allocation
        enterPhase(OrderFulfillmentPhase.ALLOCATION);
        inventoryAllocationActivities.requestAllocation(new RequestAllocationActivityInput(
                processId, workflowInput.orderId(), workflowInput.orderReceivedAt()));

        Workflow.await(() -> assignmentCheckpoint.isCommitted() || cancellationCheckpoint.isRequested());

        if (cancellationCheckpoint.isRequested()) {
            CancellationRequestInput request = cancellationCheckpoint.request();
            cancelOrderAndFinish(processId, request, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
            return;
        }

        StockOperationAssignedInput assignment = assignmentCheckpoint.assignmentSnapshot();

        // Phase 2 - release to warehouse
        enterPhase(OrderFulfillmentPhase.WAREHOUSE_RELEASE);
        ReleaseToWarehouseActivityResult shipmentResult =
                shipmentActivities.releaseToWarehouse(new ReleaseToWarehouseActivityInput(processId, assignment));
        shipmentCheckpoint.recordCreated(shipmentResult.shipmentId());

        enterPhase(OrderFulfillmentPhase.WAREHOUSE_EXECUTION);
        Workflow.await(() -> shipmentCheckpoint.hasTerminal() || cancellationCheckpoint.isRequested());

        if (cancellationCheckpoint.isRequested() && !shipmentCheckpoint.hasTerminal()) {
            enterPhase(OrderFulfillmentPhase.CANCELLING);
            requestShipmentCancellation(processId, shipmentResult.shipmentId(), cancellationCheckpoint.request());
        }

        Workflow.await(() -> shipmentCheckpoint.hasTerminal());

        if (shipmentCheckpoint.hasCancellation()) {
            cancelOrderAndFinish(processId, cancellationCheckpoint.request(), shipmentCheckpoint.terminalAtOrNull());
            return;
        }

        // Phase 3 - inventory finalization
        enterPhase(OrderFulfillmentPhase.INVENTORY_FINALIZATION);
        inventoryMovementActivities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                processId,
                workflowInput.orderId(),
                assignment.stockOperationId(),
                shipmentCheckpoint.shipmentIdOrNull(),
                assignment.moves().stream().map(AssignedStockMove::moveId).toList(),
                shipmentCheckpoint.terminalAtOrNull()));

        // Phase 4 - order completion
        enterPhase(OrderFulfillmentPhase.ORDER_COMPLETION);
        orderActivities.recordOrderFulfillment(new RecordOrderFulfillmentActivityInput(
                processId,
                workflowInput.orderId(),
                shipmentCheckpoint.shipmentIdOrNull(),
                shipmentCheckpoint.terminalAtOrNull()));

        finishWorkflow(OrderFulfillmentOutcome.FULFILLMENT_COMPLETED);
    }

    @Override
    public void stockOperationAssigned(StockOperationAssignedInput assignment) {
        if (!workflowInput.orderId().equals(assignment.orderId())
                || cancellationCheckpoint.hasRequest()
                || !assignmentCheckpoint.canReceiveAssignment()) {
            return;
        }
        assignmentCheckpoint.recordAssigned(assignment);
    }

    @Override
    public void validateCancellationRequest(CancellationRequestInput request) {
        if (!workflowInput.orderId().equals(request.orderId())) {
            throw new IllegalArgumentException("Cancellation request belongs to another order");
        }
    }

    @Override
    public void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierInput reportedCarrierHandover) {
        if (!workflowInput.orderId().equals(reportedCarrierHandover.orderId())) {
            return;
        }
        shipmentCheckpoint.recordHandover(reportedCarrierHandover.shipmentId(), reportedCarrierHandover.handedOverAt());
    }

    @Override
    public void shipmentCancelled(ShipmentCancelledInput reportedCancellation) {
        if (!workflowInput.orderId().equals(reportedCancellation.orderId())
                || !shipmentCheckpoint.canAcceptTerminalFor(reportedCancellation.shipmentId())) {
            return;
        }
        if (!cancellationCheckpoint.matchesRequest(reportedCancellation.cancellationRequestId())) {
            throw WorkflowFailures.invariantViolation(
                    "Shipment cancellation belongs to an unknown Workflow cancellation request");
        }
        if (cancellationCheckpoint.isRejected()) {
            throw WorkflowFailures.invariantViolation("Shipment cancellation conflicts with WMS rejection");
        }
        shipmentCheckpoint.recordCancelled(reportedCancellation.shipmentId(), reportedCancellation.cancelledAt());
    }

    @Override
    public CancellationRequestResult requestCancellation(CancellationRequestInput request) {
        if (cancellationCheckpoint.conflictsWith(request)) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.CONFLICT, cancellationCheckpoint.requestIdOrNull());
        }
        if (cancellationCheckpoint.isOrderCancelled()) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.ALREADY_CANCELLED, cancellationCheckpoint.requestIdOrNull());
        }
        if (shipmentCheckpoint.hasHandover() || cancellationCheckpoint.isRejected()) {
            UUID effectiveRequestId = cancellationCheckpoint.effectiveRequestId(request.requestId());
            return new CancellationRequestResult(CancellationRequestStatus.REJECTED, effectiveRequestId);
        }
        if (cancellationCheckpoint.hasRequest()) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.ALREADY_REQUESTED, cancellationCheckpoint.requestIdOrNull());
        }

        cancellationCheckpoint.recordRequest(request);
        return new CancellationRequestResult(CancellationRequestStatus.ACCEPTED, request.requestId());
    }

    @Override
    public OrderFulfillmentSnapshot state() {
        return new OrderFulfillmentSnapshot(
                workflowInput.orderId(),
                progress.phase(),
                assignmentCheckpoint.state(),
                cancellationCheckpoint.state(),
                cancellationCheckpoint.requestIdOrNull(),
                cancellationCheckpoint.requestedAtOrNull(),
                progress.outcome(),
                assignmentCheckpoint.stockOperationId(),
                shipmentCheckpoint.shipmentIdOrNull(),
                shipmentCheckpoint.terminalStatusOrNull(),
                shipmentCheckpoint.terminalAtOrNull(),
                cancellationCheckpoint.cancelledAt(),
                progress.phaseEnteredAt());
    }

    /**
     * WMS rejection alone does not establish Shipment handover. Resume fulfillment waiting until a Signal establishes
     * the actual terminal outcome.
     */
    private void requestShipmentCancellation(String processId, UUID shipmentId, CancellationRequestInput request) {
        CancelShipmentActivityStatus status =
                shipmentActivities.requestShipmentCancellation(new CancelShipmentActivityInput(
                        processId,
                        request.requestId(),
                        workflowInput.orderId(),
                        shipmentId,
                        request.requestedAt(),
                        request.reason()));
        if (status == CancelShipmentActivityStatus.REJECTED) {
            if (shipmentCheckpoint.hasCancellation()) {
                throw WorkflowFailures.invariantViolation("Shipment cancellation conflicts with WMS rejection");
            }
            cancellationCheckpoint.markRejected();
            enterPhase(OrderFulfillmentPhase.WAREHOUSE_EXECUTION);
        }
    }

    /**
     * Finishes after Ordering confirms cancellation, without waiting for Inventory resource release driven by the
     * OrderCancelled event.
     */
    private void cancelOrderAndFinish(String processId, CancellationRequestInput request, Instant cancelledAt) {
        enterPhase(OrderFulfillmentPhase.CANCELLING);
        CancelOrderActivityResult result = orderActivities.cancelOrder(new CancelOrderActivityInput(
                processId, request.requestId(), workflowInput.orderId(), cancelledAt, request.reason()));
        if (result.status() == CancelOrderActivityStatus.REJECTED) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Ordering rejected cancellation for Order: " + workflowInput.orderId(),
                    ORDER_CANCELLATION_REJECTED);
        }
        cancellationCheckpoint.markOrderCancelled(cancelledAt);
        finishWorkflow(OrderFulfillmentOutcome.ORDER_CANCELLED);
    }

    private void finishWorkflow(OrderFulfillmentOutcome outcome) {
        progress.enterPhase(
                OrderFulfillmentPhase.FINISHED, outcome, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    private void enterPhase(OrderFulfillmentPhase phase) {
        progress.enterPhase(phase, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }
}
