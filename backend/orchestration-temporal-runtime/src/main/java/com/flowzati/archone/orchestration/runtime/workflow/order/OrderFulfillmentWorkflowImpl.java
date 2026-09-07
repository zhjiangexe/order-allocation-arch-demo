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
    private final StockOperationAssignmentState assignmentState;
    private final ShipmentState shipmentState;
    private final CancellationState cancellationState;

    /**
     * Initializes identity and query state before any Workflow method or Signal handler runs.
     */
    @WorkflowInit
    public OrderFulfillmentWorkflowImpl(OrderFulfillmentInput input) {
        this.workflowInput = input;
        this.assignmentState = new StockOperationAssignmentState();
        this.shipmentState = new ShipmentState();
        this.cancellationState = new CancellationState();
        this.progress = new WorkflowProgress();
    }

    @Override
    public void execute(OrderFulfillmentInput input) {
        String processId = Workflow.getInfo().getWorkflowId();
        assignmentState.markRequested();

        // Stage 1 - Request allocation and wait for a committed assignment or a cancellation request.
        enterPhase(OrderFulfillmentPhase.ALLOCATION);
        inventoryAllocationActivities.requestAllocation(new RequestAllocationActivityInput(
                processId, workflowInput.orderId(), workflowInput.orderReceivedAt()));

        Workflow.await(() -> assignmentState.isCommitted() || cancellationState.isRequested());

        if (cancellationState.isRequested()) {
            CancellationRequestInput request = cancellationState.request();
            cancelOrderActivityAndFinish(processId, request, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
            return;
        }

        StockOperationAssignedInput assignment = assignmentState.assignmentSnapshot();

        // Stage 2 - Coordinate warehouse execution through a confirmed Shipment outcome.
        enterPhase(OrderFulfillmentPhase.WAREHOUSE_EXECUTION);

        // Create or retrieve the Shipment and correlate any Signals received before the Activity response.
        ReleaseToWarehouseActivityResult released =
                shipmentActivities.releaseToWarehouse(new ReleaseToWarehouseActivityInput(processId, assignment));
        shipmentState.recordCreated(released.shipmentId());

        // Wait for a cancellation request that needs action or a Signal confirming carrier handover.
        Workflow.await(() -> cancellationState.isRequested() || shipmentState.hasHandover());

        // Request WMS cancellation only while handover remains unconfirmed.
        if (cancellationState.isRequested() && !shipmentState.hasHandover()) {
            requestShipmentCancellationActivity(processId, released.shipmentId(), cancellationState.request());
        }

        // Wait for a Signal confirming cancellation or handover.
        Workflow.await(() -> shipmentState.hasCancellation() || shipmentState.hasHandover());

        // Stage 3 - Finalize fulfillment based on the Shipment outcome.
        // Shipment cancellation is confirmed; finalize Order cancellation.
        if (shipmentState.hasCancellation()) {
            cancelOrderActivityAndFinish(processId, cancellationState.request(), shipmentState.terminalAtOrNull());
            return;
        }

        // Stage 4 - Complete outbound movements
        enterPhase(OrderFulfillmentPhase.INVENTORY_FINALIZATION);
        inventoryMovementActivities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                processId,
                workflowInput.orderId(),
                assignment.stockOperationId(),
                shipmentState.shipmentIdOrNull(),
                assignment.moves().stream().map(AssignedStockMove::moveId).toList(),
                shipmentState.terminalAtOrNull()));

        // Stage 5 - Record Order fulfillment and mark Workflow finished.
        enterPhase(OrderFulfillmentPhase.ORDER_COMPLETION);
        orderActivities.recordOrderFulfillment(new RecordOrderFulfillmentActivityInput(
                processId,
                workflowInput.orderId(),
                shipmentState.shipmentIdOrNull(),
                shipmentState.terminalAtOrNull()));

        markFinished(OrderFulfillmentOutcome.FULFILLMENT_COMPLETED);
    }

    @Override
    public void stockOperationAssigned(StockOperationAssignedInput assignment) {
        if (!workflowInput.orderId().equals(assignment.orderId())
                || cancellationState.hasRequest()
                || !assignmentState.canReceiveAssignment()) {
            return;
        }
        assignmentState.recordAssigned(assignment);
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
        shipmentState.recordHandover(reportedCarrierHandover.shipmentId(), reportedCarrierHandover.handedOverAt());
    }

    @Override
    public void shipmentCancelled(ShipmentCancelledInput reportedCancellation) {
        if (!workflowInput.orderId().equals(reportedCancellation.orderId())
                || !shipmentState.canAcceptTerminalFor(reportedCancellation.shipmentId())) {
            return;
        }
        if (!cancellationState.matchesRequest(reportedCancellation.cancellationRequestId())) {
            throw WorkflowFailures.invariantViolation(
                    "Shipment cancellation belongs to an unknown Workflow cancellation request");
        }
        if (cancellationState.isRejected()) {
            throw WorkflowFailures.invariantViolation("Shipment cancellation conflicts with WMS rejection");
        }
        shipmentState.recordCancelled(reportedCancellation.shipmentId(), reportedCancellation.cancelledAt());
    }

    /**
     * Accepts or rejects cancellation intent without performing cancellation.
     */
    @Override
    public CancellationRequestResult requestCancellation(CancellationRequestInput request) {
        if (cancellationState.conflictsWith(request)) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.CONFLICT, cancellationState.requestIdOrNull());
        }
        if (cancellationState.isOrderCancelled()) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.ALREADY_CANCELLED, cancellationState.requestIdOrNull());
        }
        if (shipmentState.hasHandover() || cancellationState.isRejected()) {
            UUID effectiveRequestId = cancellationState.effectiveRequestId(request.requestId());
            return new CancellationRequestResult(CancellationRequestStatus.REJECTED, effectiveRequestId);
        }
        if (cancellationState.hasRequest()) {
            return new CancellationRequestResult(
                    CancellationRequestStatus.ALREADY_REQUESTED, cancellationState.requestIdOrNull());
        }

        cancellationState.recordRequest(request);
        return new CancellationRequestResult(CancellationRequestStatus.ACCEPTED, request.requestId());
    }

    @Override
    public OrderFulfillmentSnapshot state() {
        return new OrderFulfillmentSnapshot(
                workflowInput.orderId(),
                progress.phase(),
                assignmentState.state(),
                cancellationState.state(),
                cancellationState.requestIdOrNull(),
                cancellationState.requestedAtOrNull(),
                progress.outcome(),
                assignmentState.stockOperationId(),
                shipmentState.shipmentIdOrNull(),
                shipmentState.terminalStatusOrNull(),
                shipmentState.terminalAtOrNull(),
                cancellationState.cancelledAt(),
                progress.phaseEnteredAt());
    }

    /**
     * Submits a WMS cancellation request and handles its acknowledgement; the main flow waits for the terminal Signal.
     * If WMS rejects the request, return to the warehouse phase without inferring carrier handover.
     */
    private void requestShipmentCancellationActivity(
            String processId, UUID shipmentId, CancellationRequestInput request) {
        enterPhase(OrderFulfillmentPhase.CANCELLING);
        CancelShipmentActivityStatus status =
                shipmentActivities.requestShipmentCancellation(new CancelShipmentActivityInput(
                        processId,
                        request.requestId(),
                        workflowInput.orderId(),
                        shipmentId,
                        request.requestedAt(),
                        request.reason()));
        if (status == CancelShipmentActivityStatus.REJECTED) {
            if (shipmentState.hasCancellation()) {
                throw WorkflowFailures.invariantViolation("Shipment cancellation conflicts with WMS rejection");
            }
            cancellationState.markRejected();
            enterPhase(OrderFulfillmentPhase.WAREHOUSE_EXECUTION);
        }
    }

    /**
     * Cancels the Order and marks the Workflow finished after Ordering confirms success, including an identical replay.
     * Does not wait for Inventory resource release driven by OrderCancelled. The caller must exit its execution path.
     */
    private void cancelOrderActivityAndFinish(String processId, CancellationRequestInput request, Instant cancelledAt) {
        enterPhase(OrderFulfillmentPhase.CANCELLING);
        CancelOrderActivityResult result = orderActivities.cancelOrder(new CancelOrderActivityInput(
                processId, request.requestId(), workflowInput.orderId(), cancelledAt, request.reason()));
        if (result.status() == CancelOrderActivityStatus.REJECTED) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Ordering rejected cancellation for Order: " + workflowInput.orderId(),
                    ORDER_CANCELLATION_REJECTED);
        }
        cancellationState.markOrderCancelled(cancelledAt);
        markFinished(OrderFulfillmentOutcome.ORDER_CANCELLED);
    }

    /**
     * Records the final phase and outcome; does not stop execution of the calling method.
     */
    private void markFinished(OrderFulfillmentOutcome outcome) {
        progress.enterPhase(
                OrderFulfillmentPhase.FINISHED, outcome, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    private void enterPhase(OrderFulfillmentPhase phase) {
        progress.enterPhase(phase, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }
}
