package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.OrderingActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestAcknowledgement;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestStatus;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowCancellationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowResult;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentCancelledSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentTerminalStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 協調 Order Promising 與 WMS 的粗粒度履約流程。
 *
 * <p>Activity 的技術失敗交給 Temporal retry，耗盡後讓 Workflow failure 保持可見；業務內部的
 * Pick／Pack／Stage 不在此鏡像。只有稍後才由人員、設備或外部系統產生的事實使用 Signal。
 *
 * <p>目前一個 execution 明確限制一個 committed allocation 與一個 Shipment。若要拆單或改派
 * 倉庫，先引入 fulfillment attempt／多 Shipment completion policy，不能擴充成覆蓋欄位。
 */
public final class OrderFulfillmentWorkflowImpl implements OrderFulfillmentWorkflow {

    private static final String ORDER_CANCELLATION_REJECTED = "ORDER_CANCELLATION_REJECTED";

    private static final RetryOptions ACTIVITY_RETRY_OPTIONS = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .build();

    private final InventoryActivities inventoryActivities =
            Workflow.newActivityStub(InventoryActivities.class, activityOptions(InventoryActivities.TASK_QUEUE));
    private final OrderingActivities orderingActivities =
            Workflow.newActivityStub(OrderingActivities.class, activityOptions(OrderingActivities.TASK_QUEUE));
    private final WmsActivities wmsActivities =
            Workflow.newActivityStub(WmsActivities.class, activityOptions(WmsActivities.TASK_QUEUE));

    private final OrderFulfillmentWorkflowInput input;
    private WorkflowProgress progress;
    private AllocationCheckpoint allocationCheckpoint;
    private final ShipmentCheckpoint shipment;
    private final CancellationCheckpoint cancellation;

    /**
     * 在任何 Workflow method／Signal handler 執行前完成身分與查詢狀態初始化。
     */
    @WorkflowInit
    public OrderFulfillmentWorkflowImpl(OrderFulfillmentWorkflowInput input) {
        this.input = input;
        this.allocationCheckpoint = AllocationCheckpoint.notRequested();
        this.shipment = new ShipmentCheckpoint();
        this.cancellation = new CancellationCheckpoint();
        this.progress = new WorkflowProgress(OrderFulfillmentWorkflowPhase.NOT_STARTED, null, null, "Not started");
    }

    @Override
    public OrderFulfillmentWorkflowResult execute(OrderFulfillmentWorkflowInput input) {
        String processId = Workflow.getInfo().getWorkflowId();

        // 1. Temporal 是本流程唯一的配貨 command driver：先呼叫 Activity，再等待結果 fact。
        // 取消採 best-effort semantics；即使取消請求先抵達，配貨命令仍可能已進入送出流程。
        allocationCheckpoint = AllocationCheckpoint.waiting();
        progress = progress.enter(OrderFulfillmentWorkflowPhase.ALLOCATION, workflowNow(), "Requesting allocation");
        inventoryActivities.requestAllocation(
                new RequestAllocationActivityInput(processId, this.input.orderId(), this.input.orderReceivedAt()));

        // 使用 lambda 重新讀取欄位；Signal handler 會以新的 immutable checkpoint 取代舊物件。
        Workflow.await(() -> allocationCheckpoint.isCommitted() || cancellation.isRequested());

        if (cancellation.isRequested()) {
            progress = progress.enter(
                    OrderFulfillmentWorkflowPhase.CANCELLATION,
                    workflowNow(),
                    "Cancelling Order before WMS shipment creation");
            cancelOrderInOrdering(processId, cancellation.requireRequest().requestedAt());
            return finishCancellation("Order cancellation completed before WMS shipment");
        }

        AllocationSnapshot allocation = allocationCheckpoint.requireCommittedSnapshot();

        // 2. WMS 建單 use case 已能依 allocationId 冪等讀回結果，所以直接使用 Activity return。
        progress = progress.enter(
                OrderFulfillmentWorkflowPhase.WMS_SHIPMENT_CREATION, workflowNow(), "Creating WMS shipment");
        CreateShipmentActivityResult shipmentResult =
                wmsActivities.createShipment(new CreateShipmentActivityInput(processId, allocation));
        shipment.recordCreated(shipmentResult.shipmentId());

        // 3. Shipment 建立後只等待 WMS 的具體物理終態；停止作業與 putback 留在 WMS 內部。
        progress = progress.enter(
                OrderFulfillmentWorkflowPhase.SHIPMENT_HANDOVER, workflowNow(), "Waiting for Shipment terminal fact");
        Workflow.await(() -> shipment.hasTerminal() || cancellation.isRequested());

        if (cancellation.isRequested() && !shipment.hasTerminal()) {
            requestShipmentCancellation(processId);
            progress = progress.enter(
                    OrderFulfillmentWorkflowPhase.CANCELLATION,
                    workflowNow(),
                    "Waiting for WMS Shipment cancellation outcome");
            Workflow.await(shipment::hasTerminal);
        }

        ShipmentCheckpoint.Terminal terminal = shipment.requireTerminal();
        if (terminal.status() == ShipmentTerminalStatus.CANCELLED) {
            cancelOrderInOrdering(processId, terminal.occurredAt());
            return finishCancellation("Shipment and Order cancellation completed");
        }
        // HANDED_OVER 代表正常履約路線已勝出；取消請求仍保留作為事實，不另建第三種 Workflow 路線。

        // 4. 交接後先由 Stock 完成出庫搬運與扣帳；成功前不能把 Order 標成 fulfilled。
        progress = progress.enter(
                OrderFulfillmentWorkflowPhase.OUTBOUND_COMPLETION,
                workflowNow(),
                "Completing outbound movements after carrier handover");
        inventoryActivities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                processId,
                input.orderId(),
                allocation.allocationId(),
                terminal.shipmentId(),
                allocation.lines().stream().map(AllocationSnapshotLine::moveId).toList(),
                terminal.occurredAt()));

        // 5. 庫存已完成才推進 Ordering 終態。沿用出庫完成的業務時間，讓 Temporal 與 event-driven
        // 路徑對同一 Shipment 產生完全相同的 immutable fulfillment fact。
        Instant fulfilledAt = terminal.occurredAt();
        progress = progress.enter(
                OrderFulfillmentWorkflowPhase.ORDER_COMPLETION, workflowNow(), "Recording order fulfillment");
        orderingActivities.recordOrderFulfillment(new RecordOrderFulfillmentActivityInput(
                processId, input.orderId(), terminal.shipmentId(), fulfilledAt));

        return finish(
                OrderFulfillmentWorkflowStatus.FULFILLMENT_COMPLETED,
                "Shipment handed over; outbound movements and order fulfillment completed");
    }

    @Override
    public void allocationCommitted(AllocationSnapshot allocation) {
        if (allocation == null
                || !input.orderId().equals(allocation.orderId())
                || cancellation.state() != OrderFulfillmentWorkflowCancellationState.NONE
                || (!allocationCheckpoint.isWaiting() && !allocationCheckpoint.isCommitted())) {
            return;
        }
        AllocationCheckpoint updatedCheckpoint = allocationCheckpoint.recordCommitted(allocation);
        if (updatedCheckpoint == allocationCheckpoint) {
            return;
        }
        allocationCheckpoint = updatedCheckpoint;
        progress = progress.update(workflowNow(), "Allocation committed");
    }

    @Override
    public void validateCancellationRequest(CancellationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Cancellation request is required");
        }
        if (!input.orderId().equals(request.orderId())) {
            throw new IllegalArgumentException("Cancellation request belongs to another order");
        }
        CancellationRequest acceptedRequest = cancellation.request();
        if (acceptedRequest != null
                && acceptedRequest.requestId().equals(request.requestId())
                && !acceptedRequest.equals(request)) {
            throw new IllegalArgumentException("Cancellation request content conflicts with the accepted request");
        }
    }

    @Override
    public void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierSignal reportedCarrierHandover) {
        if (reportedCarrierHandover == null || !input.orderId().equals(reportedCarrierHandover.orderId())) {
            return;
        }
        shipment.recordHandover(reportedCarrierHandover.shipmentId(), reportedCarrierHandover.handedOverAt());
    }

    @Override
    public void shipmentCancelled(ShipmentCancelledSignal reportedCancellation) {
        if (reportedCancellation == null
                || !input.orderId().equals(reportedCancellation.orderId())
                || !shipment.canAcceptTerminalFor(reportedCancellation.shipmentId())) {
            return;
        }
        if (cancellation.request() == null
                || !cancellation.request().requestId().equals(reportedCancellation.cancellationRequestId())) {
            throw WorkflowFailures.invariantViolation(
                    "Shipment cancellation belongs to an unknown Workflow cancellation request");
        }
        shipment.recordCancelled(reportedCancellation.shipmentId(), reportedCancellation.cancelledAt());
    }

    @Override
    public CancellationRequestAcknowledgement requestCancellation(CancellationRequest request) {
        if (cancellation.state() == OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED) {
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.ALREADY_CANCELLED,
                    cancellation.requireRequest().requestId(),
                    "Order cancellation is already committed");
        }
        if (shipment.hasHandover()) {
            UUID effectiveRequestId = cancellation.state() == OrderFulfillmentWorkflowCancellationState.NONE
                    ? request.requestId()
                    : cancellation.requireRequest().requestId();
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.REJECTED,
                    effectiveRequestId,
                    "Shipment cancellation is no longer available");
        }
        if (cancellation.state() != OrderFulfillmentWorkflowCancellationState.NONE) {
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.ALREADY_REQUESTED,
                    cancellation.requireRequest().requestId(),
                    "A cancellation request is already being coordinated");
        }

        cancellation.recordRequest(request);
        progress = progress.update(workflowNow(), "Cancellation requested; waiting for a safe coordination checkpoint");
        return new CancellationRequestAcknowledgement(
                CancellationRequestStatus.ACCEPTED, request.requestId(), "Cancellation request accepted");
    }

    @Override
    public OrderFulfillmentWorkflowSnapshot state() {
        return new OrderFulfillmentWorkflowSnapshot(
                input.orderId(),
                progress.phase(),
                allocationCheckpoint.state(),
                cancellation.state(),
                cancellation.requestIdOrNull(),
                cancellation.requestedAtOrNull(),
                progress.outcome(),
                allocationCheckpoint.allocationId(),
                shipment.shipmentIdOrNull(),
                shipment.terminalStatusOrNull(),
                shipment.terminalAtOrNull(),
                cancellation.cancelledAt(),
                progress.updatedAt(),
                progress.detail());
    }

    /**
     * 提交 WMS cancellation command；實際終態只由後續 canonical event Signal 決定。
     */
    private void requestShipmentCancellation(String processId) {
        CancellationRequest request = cancellation.requireRequest();
        progress = progress.enter(
                OrderFulfillmentWorkflowPhase.CANCELLATION, workflowNow(), "Requesting WMS Shipment cancellation");
        wmsActivities.requestShipmentCancellation(new CancelShipmentActivityInput(
                processId,
                request.requestId(),
                input.orderId(),
                shipment.requireShipmentId(),
                request.requestedAt(),
                request.reason()));
    }

    private void cancelOrderInOrdering(String processId, Instant cancelledAt) {
        if (cancellation.state() == OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED) {
            return;
        }

        CancellationRequest request = cancellation.requireRequest();
        progress = progress.update(workflowNow(), "Cancelling Order after warehouse work is safe");
        CancelOrderActivityResult result = orderingActivities.cancelOrder(new CancelOrderActivityInput(
                processId, request.requestId(), input.orderId(), cancelledAt, request.reason()));
        if (!input.orderId().equals(result.orderId())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "CancelOrder Activity returned an uncorrelated result", ORDER_CANCELLATION_REJECTED);
        }
        if (result.status() == CancelOrderActivityStatus.REJECTED) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Ordering rejected cancellation for Order: " + input.orderId(), ORDER_CANCELLATION_REJECTED);
        }
        cancellation.markOrderCancelled(cancelledAt);
    }

    private OrderFulfillmentWorkflowResult finishCancellation(String detail) {
        if (cancellation.state() != OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED
                || cancellation.cancelledAt() == null) {
            throw WorkflowFailures.invariantViolation("Cannot finish cancellation before Order is cancelled");
        }
        return finish(OrderFulfillmentWorkflowStatus.ORDER_CANCELLED, detail);
    }

    private OrderFulfillmentWorkflowResult finish(OrderFulfillmentWorkflowStatus outcome, String detail) {
        progress = new WorkflowProgress(OrderFulfillmentWorkflowPhase.FINISHED, outcome, workflowNow(), detail);
        return new OrderFulfillmentWorkflowResult(
                input.orderId(),
                outcome,
                allocationCheckpoint.allocationId(),
                shipment.shipmentIdOrNull(),
                progress.updatedAt(),
                detail);
    }

    private Instant workflowNow() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    private static ActivityOptions activityOptions(String taskQueue) {
        return ActivityOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(ACTIVITY_RETRY_OPTIONS)
                .build();
    }
}
