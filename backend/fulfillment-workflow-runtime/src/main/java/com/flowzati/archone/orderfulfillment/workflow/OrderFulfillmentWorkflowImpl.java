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
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityStatus;
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
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowOutcome;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowResult;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowState;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
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

    private static final String WMS_SHIPMENT_FACT_CONFLICT = "WMS_SHIPMENT_FACT_CONFLICT";
    private static final String ORDER_CANCELLATION_REJECTED = "ORDER_CANCELLATION_REJECTED";

    private static final RetryOptions ACTIVITY_RETRY_OPTIONS = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .setMaximumAttempts(5)
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
    private UUID shipmentId;
    private ShipmentHandedOverToCarrierSignal carrierHandover;
    private final CancellationCheckpoint cancellation;

    /** 在任何 Workflow method／Signal handler 執行前完成身分與查詢狀態初始化。 */
    @WorkflowInit
    public OrderFulfillmentWorkflowImpl(OrderFulfillmentWorkflowInput input) {
        this.input = input;
        this.allocationCheckpoint = AllocationCheckpoint.notRequested();
        this.cancellation = new CancellationCheckpoint();
        this.progress = new WorkflowProgress(OrderFulfillmentWorkflowPhase.NOT_STARTED, null, null, "Not started");
    }

    @Override
    public OrderFulfillmentWorkflowResult execute(OrderFulfillmentWorkflowInput input) {
        String processId = Workflow.getInfo().getWorkflowId();

        // 1. Temporal 是本流程唯一的配貨 command driver：先呼叫 Activity，再等待結果 fact。
        // 取消採 best-effort semantics；即使取消請求先抵達，配貨命令仍可能已進入送出流程。
        allocationCheckpoint = AllocationCheckpoint.waiting();
        enterPhase(OrderFulfillmentWorkflowPhase.ALLOCATION, "Requesting allocation");
        inventoryActivities.requestAllocation(
                new RequestAllocationActivityInput(processId, this.input.orderId(), this.input.orderReceivedAt()));

        // 使用 lambda 重新讀取欄位；Signal handler 會以新的 immutable checkpoint 取代舊物件。
        Workflow.await(() -> allocationCheckpoint.isCommitted() || cancellationRequested());

        if (cancellationRequested()) {
            enterPhase(OrderFulfillmentWorkflowPhase.CANCELLATION, "Cancelling Order before WMS shipment creation");
            cancelOrderInOrdering(processId);
            return finishCancellation("Order cancellation completed before WMS shipment");
        }

        AllocationSnapshot allocation = allocationCheckpoint.requireCommittedSnapshot();

        // 2. WMS 建單 use case 已能依 allocationId 冪等讀回結果，所以直接使用 Activity return。
        enterPhase(OrderFulfillmentWorkflowPhase.WMS_SHIPMENT_CREATION, "Creating WMS shipment");
        CreateShipmentActivityResult shipment =
                wmsActivities.createShipment(new CreateShipmentActivityInput(processId, allocation));
        shipmentId = shipment.shipmentId();

        // 3. Shipment 建立後等待 handover；取消命令只負責中斷等待並交由 WMS 做安全判斷。
        enterPhase(OrderFulfillmentWorkflowPhase.SHIPMENT_HANDOVER, "Waiting for ShipmentHandedOverToCarrier");
        Workflow.await(() -> hasCorrelatedCarrierHandover() || cancellationRequested());

        if (cancellationRequested()) {
            CancelShipmentActivityStatus status = cancelShipmentInWms(processId);
            if (status == CancelShipmentActivityStatus.CANCELLED) {
                cancelOrderInOrdering(processId);
                return finishCancellation("Shipment and Order cancellation completed");
            }

            // WMS 表示取消太晚；等待對應的 handover fact 後繼續履約。
            enterPhase(
                    OrderFulfillmentWorkflowPhase.SHIPMENT_HANDOVER,
                    "Cancellation rejected; waiting for ShipmentHandedOverToCarrier");
            Workflow.await(this::hasCorrelatedCarrierHandover);
        }

        ShipmentHandedOverToCarrierSignal carrierHandover = correlatedCarrierHandover();
        if (carrierHandover == null) {
            throw WorkflowFailures.invariantViolation("Carrier handover wait completed without a correlated shipment");
        }

        // 4. 交接後先由 Stock 完成出庫搬運與扣帳；成功前不能把 Order 標成 fulfilled。
        enterPhase(
                OrderFulfillmentWorkflowPhase.OUTBOUND_COMPLETION,
                "Completing outbound movements after carrier handover");
        inventoryActivities.completeOutboundMovements(new CompleteOutboundMovementsActivityInput(
                processId,
                input.orderId(),
                allocation.allocationId(),
                shipmentId,
                allocation.lines().stream().map(AllocationSnapshotLine::moveId).toList(),
                carrierHandover.handedOverAt()));

        // 5. 庫存已完成才推進 Ordering 終態。沿用出庫完成的業務時間，讓 Temporal 與 event-driven
        // 路徑對同一 Shipment 產生完全相同的 immutable fulfillment fact。
        Instant fulfilledAt = carrierHandover.handedOverAt();
        enterPhase(OrderFulfillmentWorkflowPhase.ORDER_COMPLETION, "Recording order fulfillment");
        orderingActivities.recordOrderFulfillment(
                new RecordOrderFulfillmentActivityInput(processId, input.orderId(), shipmentId, fulfilledAt));

        return finish(
                OrderFulfillmentWorkflowOutcome.FULFILLMENT_COMPLETED,
                "Shipment handed over; outbound movements and order fulfillment completed");
    }

    @Override
    public void allocationCommitted(AllocationSnapshot allocation) {
        if (allocation == null
                || !input.orderId().equals(allocation.orderId())
                || cancellation.state() != OrderFulfillmentWorkflowCancellationState.NONE
                || !allocationCheckpoint.isWaiting()) {
            return;
        }
        allocationCheckpoint = allocationCheckpoint.committed(allocation);
        updateProgress("Allocation committed");
    }

    @Override
    public void validateCancellationRequest(CancellationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Cancellation request is required");
        }
        if (!input.orderId().equals(request.orderId())) {
            throw new IllegalArgumentException("Cancellation request belongs to another order");
        }
    }

    @Override
    public void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierSignal reportedCarrierHandover) {
        if (reportedCarrierHandover == null
                || !input.orderId().equals(reportedCarrierHandover.orderId())
                || hasCorrelatedCarrierHandover()) {
            return;
        }
        if (shipmentId == null || shipmentId.equals(reportedCarrierHandover.shipmentId())) {
            carrierHandover = reportedCarrierHandover;
        }
    }

    @Override
    public CancellationRequestAcknowledgement requestCancellation(CancellationRequest request) {
        if (cancellation.state() == OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED) {
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.ALREADY_CANCELLED,
                    requireCancellationRequest().requestId(),
                    "Order cancellation is already committed");
        }
        if (cancellation.state() == OrderFulfillmentWorkflowCancellationState.REJECTED
                || hasCorrelatedCarrierHandover()) {
            UUID effectiveRequestId = cancellation.state() == OrderFulfillmentWorkflowCancellationState.NONE
                    ? request.requestId()
                    : requireCancellationRequest().requestId();
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.REJECTED,
                    effectiveRequestId,
                    "Shipment cancellation is no longer available");
        }
        if (cancellation.state() != OrderFulfillmentWorkflowCancellationState.NONE) {
            return new CancellationRequestAcknowledgement(
                    CancellationRequestStatus.ALREADY_REQUESTED,
                    requireCancellationRequest().requestId(),
                    "A cancellation request is already being coordinated");
        }

        cancellation.accept(request);
        updateProgress("Cancellation requested; waiting for a safe coordination checkpoint");
        return new CancellationRequestAcknowledgement(
                CancellationRequestStatus.ACCEPTED, request.requestId(), "Cancellation request accepted");
    }

    @Override
    public OrderFulfillmentWorkflowState state() {
        return new OrderFulfillmentWorkflowState(
                input.orderId(),
                progress.phase(),
                allocationCheckpoint.state(),
                cancellation.state(),
                cancellationRequestIdOrNull(),
                cancellationRequestedAtOrNull(),
                progress.outcome(),
                allocationCheckpoint.allocationId(),
                shipmentId,
                cancellation.cancelledAt(),
                progress.updatedAt(),
                progress.detail());
    }

    /** 由同一條 fulfillment execution 向 WMS 取得取消決策；外部入口不得繞過此處。 */
    private CancelShipmentActivityStatus cancelShipmentInWms(String processId) {
        CancellationRequest request = requireCancellationRequest();
        enterPhase(OrderFulfillmentWorkflowPhase.CANCELLATION, "Resolving WMS Shipment cancellation");
        // WMS 取消 Activity 會同步回傳最終決策；Activity 執行期間維持 REQUESTED。

        CancelShipmentActivityStatus status = wmsActivities.cancelShipment(new CancelShipmentActivityInput(
                processId, request.requestId(), input.orderId(), shipmentId, request.requestedAt(), request.reason()));

        return switch (status) {
            case CANCELLED -> {
                ensureCancellationNotAfterHandover(shipmentId);
                yield CancelShipmentActivityStatus.CANCELLED;
            }
            case REJECTED -> rejectShipmentCancellation();
        };
    }

    private CancelShipmentActivityStatus rejectShipmentCancellation() {
        cancellation.reject();
        updateProgress("Cancellation rejected by WMS; continuing fulfillment");
        return CancelShipmentActivityStatus.REJECTED;
    }

    private void cancelOrderInOrdering(String processId) {
        if (cancellation.state() == OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED) {
            return;
        }

        CancellationRequest request = requireCancellationRequest();
        updateProgress("Cancelling Order after warehouse work is safe");
        CancelOrderActivityResult result = orderingActivities.cancelOrder(new CancelOrderActivityInput(
                processId, request.requestId(), input.orderId(), request.requestedAt(), request.reason()));
        if (!input.orderId().equals(result.orderId())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "CancelOrder Activity returned an uncorrelated result", ORDER_CANCELLATION_REJECTED);
        }
        if (result.status() == CancelOrderActivityStatus.REJECTED) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Ordering rejected cancellation for Order: " + input.orderId(), ORDER_CANCELLATION_REJECTED);
        }
        cancellation.markOrderCancelled(request.requestedAt());
    }

    private boolean cancellationRequested() {
        return cancellation.state() == OrderFulfillmentWorkflowCancellationState.REQUESTED;
    }

    private CancellationRequest requireCancellationRequest() {
        if (cancellation.request() == null) {
            throw WorkflowFailures.invariantViolation(
                    "Cancellation state " + cancellation.state() + " requires an accepted request");
        }
        return cancellation.request();
    }

    private UUID cancellationRequestIdOrNull() {
        return cancellation.request() == null ? null : cancellation.request().requestId();
    }

    private Instant cancellationRequestedAtOrNull() {
        return cancellation.request() == null ? null : cancellation.request().requestedAt();
    }

    private void enterPhase(OrderFulfillmentWorkflowPhase phase, String detail) {
        progress = new WorkflowProgress(phase, null, workflowNow(), detail);
    }

    private void updateProgress(String detail) {
        progress = new WorkflowProgress(progress.phase(), progress.outcome(), workflowNow(), detail);
    }

    private OrderFulfillmentWorkflowResult finishCancellation(String detail) {
        if (cancellation.state() != OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED
                || cancellation.cancelledAt() == null) {
            throw WorkflowFailures.invariantViolation("Cannot finish cancellation before Order is cancelled");
        }
        return finish(OrderFulfillmentWorkflowOutcome.ORDER_CANCELLED, detail);
    }

    private OrderFulfillmentWorkflowResult finish(OrderFulfillmentWorkflowOutcome outcome, String detail) {
        progress = new WorkflowProgress(OrderFulfillmentWorkflowPhase.FINISHED, outcome, workflowNow(), detail);
        return new OrderFulfillmentWorkflowResult(
                input.orderId(),
                outcome,
                allocationCheckpoint.allocationId(),
                shipmentId,
                progress.updatedAt(),
                detail);
    }

    private void ensureCancellationNotAfterHandover(UUID resolvedShipmentId) {
        if (hasCorrelatedCarrierHandover()) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "WMS reported Shipment cancellation after carrier handover was observed: " + resolvedShipmentId,
                    WMS_SHIPMENT_FACT_CONFLICT);
        }
    }

    private boolean hasCorrelatedCarrierHandover() {
        return correlatedCarrierHandover() != null;
    }

    private ShipmentHandedOverToCarrierSignal correlatedCarrierHandover() {
        if (shipmentId == null || carrierHandover == null || !shipmentId.equals(carrierHandover.shipmentId())) {
            return null;
        }
        return carrierHandover;
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
