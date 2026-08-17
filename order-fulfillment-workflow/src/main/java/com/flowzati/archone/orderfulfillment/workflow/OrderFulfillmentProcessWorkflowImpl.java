package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CompleteOutboundMovements;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrder;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrderResult;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrderStatus;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.RecordOrderFulfillment;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.RequestAllocation;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.CancelShipment;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.CreateShipment;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.ShipmentCancellationDecisionStatus;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.ShipmentCreationReceipt;
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
public final class OrderFulfillmentProcessWorkflowImpl
    implements OrderFulfillmentProcessWorkflow {

  private static final String WMS_SHIPMENT_FACT_CONFLICT = "WMS_SHIPMENT_FACT_CONFLICT";
  private static final String WORKFLOW_INVARIANT_VIOLATION =
      "ORDER_FULFILLMENT_WORKFLOW_INVARIANT_VIOLATION";
  private static final String ORDER_CANCELLATION_REJECTED =
      "ORDER_CANCELLATION_REJECTED";

  private static final RetryOptions ACTIVITY_RETRY_OPTIONS = RetryOptions.newBuilder()
      .setInitialInterval(Duration.ofSeconds(1))
      .setBackoffCoefficient(2.0)
      .setMaximumInterval(Duration.ofSeconds(30))
      .setMaximumAttempts(5)
      .build();

  private final OrderPromisingActivities orderPromisingActivities =
      Workflow.newActivityStub(OrderPromisingActivities.class, activityOptions(OrderPromisingActivities.TASK_QUEUE));
  private final WmsActivities wmsActivities =
      Workflow.newActivityStub(WmsActivities.class, activityOptions(WmsActivities.TASK_QUEUE));

  private final StartInput input;
  private Progress progress;
  private AllocationCheckpoint allocationCheckpoint;
  private UUID shipmentId;
  private ShipmentHandedOverToCarrier carrierHandover;
  private final CancellationContext cancellation;

  /** 在任何 Workflow method／Signal handler 執行前完成身分與查詢狀態初始化。 */
  @WorkflowInit
  public OrderFulfillmentProcessWorkflowImpl(StartInput input) {
    this.input = input;
    this.allocationCheckpoint = AllocationCheckpoint.notRequested();
    this.cancellation = new CancellationContext();
    this.progress = new Progress(Phase.NOT_STARTED, null, null, "Not started");
  }

  @Override
  public Result execute(StartInput input) {
    String processId = Workflow.getInfo().getWorkflowId();

    // 1. Temporal 是本流程唯一的配貨 command driver：先呼叫 Activity，再等待結果 fact。
    // 取消採 best-effort semantics；即使取消請求先抵達，配貨命令仍可能已進入送出流程。
    allocationCheckpoint = AllocationCheckpoint.waiting();
    enterPhase(Phase.ALLOCATION, "Requesting allocation");
    orderPromisingActivities.requestAllocation(
        new RequestAllocation(processId, this.input.orderId(), this.input.orderReceivedAt()));

    // 使用 lambda 重新讀取欄位；Signal handler 會以新的 immutable checkpoint 取代舊物件。
    Workflow.await(() -> allocationCheckpoint.isCommitted() || cancellationRequested());

    if (cancellationRequested()) {
      enterPhase(Phase.CANCELLATION, "Cancelling Order before WMS shipment creation");
      cancelOrderInOrdering(processId);
      return finishCancellation("Order cancellation completed before WMS shipment");
    }

    AllocationSnapshot allocation = allocationCheckpoint.requireCommittedSnapshot();

    // 2. WMS 建單 use case 已能依 allocationId 冪等讀回結果，所以直接使用 Activity return。
    enterPhase(Phase.WMS_SHIPMENT_CREATION, "Creating WMS shipment");
    ShipmentCreationReceipt shipment = wmsActivities.createShipment(new CreateShipment(processId, allocation));
    shipmentId = shipment.shipmentId();

    // 3. Shipment 建立後等待 handover；取消命令只負責中斷等待並交由 WMS 做安全判斷。
    enterPhase(Phase.SHIPMENT_HANDOVER, "Waiting for ShipmentHandedOverToCarrier");
    Workflow.await(() -> hasCorrelatedCarrierHandover() || cancellationRequested());

    if (cancellationRequested()) {
      ShipmentCancellationDecisionStatus status = cancelShipmentInWms(processId);
      if (status == ShipmentCancellationDecisionStatus.CANCELLED) {
        cancelOrderInOrdering(processId);
        return finishCancellation("Shipment and Order cancellation completed");
      }

      // WMS 表示取消太晚；等待對應的 handover fact 後繼續履約。
      enterPhase(Phase.SHIPMENT_HANDOVER, "Cancellation rejected; waiting for ShipmentHandedOverToCarrier");
      Workflow.await(this::hasCorrelatedCarrierHandover);
    }

    ShipmentHandedOverToCarrier carrierHandover = correlatedCarrierHandover();
    if (carrierHandover == null) {
      throw workflowInvariantViolation("Carrier handover wait completed without a correlated shipment");
    }

    // 4. 交接後先由 Stock 完成出庫搬運與扣帳；成功前不能把 Order 標成 fulfilled。
    enterPhase(Phase.OUTBOUND_COMPLETION, "Completing outbound movements after carrier handover");
    orderPromisingActivities.completeOutboundMovements(new CompleteOutboundMovements(
        processId,
        input.orderId(),
        allocation.allocationId(),
        shipmentId,
        allocation.lines().stream().map(AllocationLine::moveId).toList(),
        carrierHandover.handedOverAt()));

    // 5. 庫存已完成才推進 Ordering 終態；Activity retry 會重送同一 fulfilledAt。
    Instant fulfilledAt = workflowNow();
    enterPhase(Phase.ORDER_COMPLETION, "Recording order fulfillment");
    orderPromisingActivities.recordOrderFulfillment(
        new RecordOrderFulfillment(processId, input.orderId(), shipmentId, fulfilledAt));

    return finish(Outcome.FULFILLMENT_COMPLETED,
        "Shipment handed over; outbound movements and order fulfillment completed");
  }

  @Override
  public void allocationCommitted(AllocationSnapshot allocation) {
    if (allocation == null
        || !input.orderId().equals(allocation.orderId())
        || cancellation.state != CancellationState.NONE
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
  public void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrier reportedCarrierHandover) {
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
  public CancellationRequestAck requestCancellation(CancellationRequest request) {
    if (cancellation.state == CancellationState.ORDER_CANCELLED) {
      return new CancellationRequestAck(
          CancellationRequestStatus.ALREADY_CANCELLED,
          requireCancellationRequest().requestId(),
          "Order cancellation is already committed");
    }
    if (cancellation.state == CancellationState.REJECTED
        || hasCorrelatedCarrierHandover()) {
      UUID effectiveRequestId = cancellation.state == CancellationState.NONE
          ? request.requestId()
          : requireCancellationRequest().requestId();
      return new CancellationRequestAck(
          CancellationRequestStatus.REJECTED,
          effectiveRequestId,
        "Shipment cancellation is no longer available");
    }
    if (cancellation.state != CancellationState.NONE) {
      return new CancellationRequestAck(
          CancellationRequestStatus.ALREADY_REQUESTED,
          requireCancellationRequest().requestId(),
          "A cancellation request is already being coordinated");
    }

    cancellation.state = CancellationState.REQUESTED;
    cancellation.request = request;
    updateProgress("Cancellation requested; waiting for a safe coordination checkpoint");
    return new CancellationRequestAck(
        CancellationRequestStatus.ACCEPTED,
        request.requestId(),
        "Cancellation request accepted");
  }

  @Override
  public State state() {
    return new State(
        input.orderId(),
        progress.phase(),
        allocationCheckpoint.state(),
        cancellation.state,
        cancellationRequestIdOrNull(),
        cancellationRequestedAtOrNull(),
        progress.outcome(),
        allocationCheckpoint.allocationId(),
        shipmentId,
        cancellation.cancelledAt,
        progress.updatedAt(),
        progress.detail());
  }

  /** 由同一條 fulfillment execution 向 WMS 取得取消決策；外部入口不得繞過此處。 */
  private ShipmentCancellationDecisionStatus cancelShipmentInWms(String processId) {
    CancellationRequest request = requireCancellationRequest();
    enterPhase(Phase.CANCELLATION, "Resolving WMS Shipment cancellation");
    // WMS 取消 Activity 會同步回傳最終決策；Activity 執行期間維持 REQUESTED。

    ShipmentCancellationDecisionStatus status = wmsActivities.cancelShipment(new CancelShipment(
        processId,
        request.requestId(),
        input.orderId(),
        shipmentId,
        request.requestedAt(),
        request.reason()));

    return switch (status) {
      case CANCELLED -> {
        ensureCancellationNotAfterHandover(shipmentId);
        yield ShipmentCancellationDecisionStatus.CANCELLED;
      }
      case REJECTED -> rejectShipmentCancellation();
    };
  }

  private ShipmentCancellationDecisionStatus rejectShipmentCancellation() {
    cancellation.state = CancellationState.REJECTED;
    updateProgress("Cancellation rejected by WMS; continuing fulfillment");
    return ShipmentCancellationDecisionStatus.REJECTED;
  }

  private void cancelOrderInOrdering(String processId) {
    if (cancellation.state == CancellationState.ORDER_CANCELLED) {
      return;
    }

    CancellationRequest request = requireCancellationRequest();
    updateProgress("Cancelling Order after warehouse work is safe");
    CancelOrderResult result = orderPromisingActivities.cancelOrder(new CancelOrder(
        processId,
        request.requestId(),
        input.orderId(),
        request.requestedAt(),
        request.reason()));
    if (!input.orderId().equals(result.orderId())) {
      throw ApplicationFailure.newNonRetryableFailure(
          "CancelOrder returned an uncorrelated result", ORDER_CANCELLATION_REJECTED);
    }
    if (result.status() == CancelOrderStatus.REJECTED) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Ordering rejected cancellation for Order: " + input.orderId(),
          ORDER_CANCELLATION_REJECTED);
    }
    cancellation.cancelledAt = request.requestedAt();
    cancellation.state = CancellationState.ORDER_CANCELLED;
  }

  private boolean cancellationRequested() {
    return cancellation.state == CancellationState.REQUESTED;
  }

  private CancellationRequest requireCancellationRequest() {
    if (cancellation.request == null) {
      throw workflowInvariantViolation("Cancellation state " + cancellation.state + " requires an accepted request");
    }
    return cancellation.request;
  }

  private UUID cancellationRequestIdOrNull() {
    return cancellation.request == null ? null : cancellation.request.requestId();
  }

  private Instant cancellationRequestedAtOrNull() {
    return cancellation.request == null ? null : cancellation.request.requestedAt();
  }

  private void enterPhase(Phase phase, String detail) {
    progress = new Progress(phase, null, workflowNow(), detail);
  }

  private void updateProgress(String detail) {
    progress = new Progress(progress.phase(), progress.outcome(), workflowNow(), detail);
  }

  private Result finishCancellation(String detail) {
    if (cancellation.state != CancellationState.ORDER_CANCELLED || cancellation.cancelledAt == null) {
      throw workflowInvariantViolation("Cannot finish cancellation before Order is cancelled");
    }
    return finish(Outcome.ORDER_CANCELLED, detail);
  }

  private Result finish(Outcome outcome, String detail) {
    progress = new Progress(
        Phase.FINISHED,
        outcome,
        workflowNow(),
        detail);
    return new Result(
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

  private ShipmentHandedOverToCarrier correlatedCarrierHandover() {
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

  private static ApplicationFailure workflowInvariantViolation(String message) {
    return ApplicationFailure.newNonRetryableFailure(message, WORKFLOW_INVARIANT_VIOLATION);
  }

  /**
   * Workflow 對配貨邊界的最小認知，不是 {@code OrderStatus} 的複本。
   *
   * <p>{@code state} 只回答「是否已拿到可交給 WMS 的 committed snapshot」。補貨等待與
   * BACKORDERED 歷程留在 Order／Stock 的 domain model 與 read model。
   */
  private record AllocationCheckpoint(AllocationCheckpointState state, AllocationSnapshot committedSnapshot) {

    private AllocationCheckpoint {
      if (state == null) {
        throw workflowInvariantViolation("Allocation checkpoint stage is required");
      }
      if ((state == AllocationCheckpointState.COMMITTED) != (committedSnapshot != null)) {
        throw workflowInvariantViolation("Only a committed allocation checkpoint can contain a snapshot");
      }
    }

    private static AllocationCheckpoint notRequested() {
      return new AllocationCheckpoint(AllocationCheckpointState.NOT_REQUESTED, null);
    }

    private static AllocationCheckpoint waiting() {
      return new AllocationCheckpoint(AllocationCheckpointState.WAITING_FOR_COMMITMENT, null);
    }

    private AllocationCheckpoint committed(AllocationSnapshot snapshot) {
      requireWaiting();
      if (snapshot == null) {
        throw workflowInvariantViolation("Committed allocation snapshot is required");
      }
      return new AllocationCheckpoint(AllocationCheckpointState.COMMITTED, snapshot);
    }

    private boolean isWaiting() {
      return state == AllocationCheckpointState.WAITING_FOR_COMMITMENT;
    }

    private boolean isCommitted() {
      return state == AllocationCheckpointState.COMMITTED;
    }

    private AllocationSnapshot requireCommittedSnapshot() {
      if (!isCommitted()) {
        throw workflowInvariantViolation("Allocation wait completed without a committed snapshot");
      }
      return committedSnapshot;
    }

    private UUID allocationId() {
      return committedSnapshot == null ? null : committedSnapshot.allocationId();
    }

    private void requireWaiting() {
      if (!isWaiting()) {
        throw workflowInvariantViolation("Allocation checkpoint is not waiting for commitment");
      }
    }

  }

  /** 純粹收納 Workflow replay 所需資料；所有業務轉換留在具名流程方法中。 */
  private static final class CancellationContext {
    private CancellationState state = CancellationState.NONE;
    private CancellationRequest request;
    private Instant cancelledAt;
  }

  private record Progress(
      Phase phase,
      Outcome outcome,
      Instant updatedAt,
      String detail) {
  }
}
