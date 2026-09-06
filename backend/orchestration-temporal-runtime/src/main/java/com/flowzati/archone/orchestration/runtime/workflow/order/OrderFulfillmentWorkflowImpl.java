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
 * 協調 Inventory、Ordering 與 WMS 的粗粒度履約流程。
 *
 * <p>Activity 可重試的技術失敗交給 Temporal 持續重試；不可重試的失敗讓 Workflow failure 保持可見。
 * 取消 Update 只記錄意圖，必須等進行中的 Activity 成功返回，主線才能協調取消；Activity 未返回不代表
 * 外部業務尚未提交。業務內部的 Pick／Pack／Stage 不在此鏡像，只有稍後才由人員、設備或外部系統產生的事實使用 Signal。
 *
 * <p>目前一個 execution 明確限制一個 assigned stock operation 與一個 Shipment。若要拆單或改派
 * 倉庫，先引入 fulfillment attempt／多 Shipment completion policy，不能擴充成覆蓋欄位。
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
     * 在任何 Workflow method／Signal handler 執行前完成身分與查詢狀態初始化。
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
            enterPhase(OrderFulfillmentPhase.CANCELLATION);
            cancelOrderAndFinish(processId, request, request.requestedAt());
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

        if (cancellationCheckpoint.isRequested()) {
            enterPhase(OrderFulfillmentPhase.CANCELLATION);
            if (!shipmentCheckpoint.hasTerminal()) {
                requestShipmentCancellation(processId, shipmentResult.shipmentId(), cancellationCheckpoint.request());
            }
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
        cancellationCheckpoint.validateRepeatedRequest(request);
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
        shipmentCheckpoint.recordCancelled(reportedCancellation.shipmentId(), reportedCancellation.cancelledAt());
    }

    @Override
    public CancellationRequestResult requestCancellation(CancellationRequestInput request) {
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
     * WMS 拒絕取消不代表 Shipment 已交接；回到履約等待，實際終態仍由 Signal 決定。
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
        // 舊版 void Activity 的空結果不代表拒絕，仍等待原本的 terminal Signal。
        if (status == CancelShipmentActivityStatus.REJECTED) {
            cancellationCheckpoint.markRejected();
            enterPhase(OrderFulfillmentPhase.WAREHOUSE_EXECUTION);
        }
    }

    private void cancelOrderInOrdering(String processId, CancellationRequestInput request, Instant cancelledAt) {
        CancelOrderActivityResult result = orderActivities.cancelOrder(new CancelOrderActivityInput(
                processId, request.requestId(), workflowInput.orderId(), cancelledAt, request.reason()));
        if (!workflowInput.orderId().equals(result.orderId())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "CancelOrder Activity returned an uncorrelated result", ORDER_CANCELLATION_REJECTED);
        }
        if (result.status() == CancelOrderActivityStatus.REJECTED) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Ordering rejected cancellation for Order: " + workflowInput.orderId(),
                    ORDER_CANCELLATION_REJECTED);
        }
    }

    private void cancelOrderAndFinish(String processId, CancellationRequestInput request, Instant cancelledAt) {
        cancelOrderInOrdering(processId, request, cancelledAt);
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
