package com.flowzati.archone.orderfulfillment.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 從訂單可靠成立後開始，協調 Order Promising 與 WMS 的粗粒度履約主線。
 *
 * <p>Workflow 不擁有 Order、Allocation 或 Shipment aggregate；它只保存跨 bounded context
 * checkpoint。各 Signal 由 integration adapter 將既有的 Integration Event 映射而來。
 */
@WorkflowInterface
public interface OrderFulfillmentProcessWorkflow {

    String WORKFLOW_TYPE = "OrderFulfillmentProcessWorkflow";
    String WORKFLOW_ID_PREFIX = "order-fulfillment/";
    String TASK_QUEUE = "order-fulfillment-workflows";

    static String workflowId(UUID orderId) {
        return WORKFLOW_ID_PREFIX + Objects.requireNonNull(orderId, "Order ID is required");
    }

    /** 啟動一張訂單唯一的長期履約協調流程。 */
    @WorkflowMethod(name = WORKFLOW_TYPE)
    Result execute(StartInput input);

    /** 接收可交給 WMS 的最終 committed allocation snapshot。 */
    @SignalMethod(name = "allocationCommitted")
    void allocationCommitted(AllocationSnapshot allocation);

    /**
     * 接收 {@code ShipmentHandedOverToCarrier} 業務事實；Workflow 隨後要求 Stock context
     * 完成對應的 outbound movements。
     */
    @SignalMethod(name = "shipmentHandedOverToCarrier")
    void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrier handover);

    /**
     * 外部入口提交「要求取消」命令，但不得先取消 Order。Update 只接受並記錄請求；WMS 安全判斷與
     * Order cancellation 由 {@link #execute(StartInput)} 主線在同一條 Workflow execution 協調。
     */
    @UpdateMethod(name = "requestCancellation")
    CancellationRequestAck requestCancellation(CancellationRequest request);

    /** 在 Update 寫入 Workflow History 前拒絕無法屬於此流程的取消請求。 */
    @UpdateValidatorMethod(updateName = "requestCancellation")
    void validateCancellationRequest(CancellationRequest request);

    /** 提供 API／維運工具查詢，不取代各 bounded context 的 aggregate/read model。 */
    @QueryMethod(name = "state")
    State state();

    record StartInput(UUID orderId, Instant orderReceivedAt) {

        public StartInput {
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(orderReceivedAt, "Order received time is required");
        }
    }

    /** Workflow 專用契約；adapter 負責從 messaging contract 映射，不直接共用 Kafka event class。 */
    record AllocationSnapshot(
            UUID allocationId,
            UUID orderId,
            UUID ownerId,
            UUID facilityId,
            List<AllocationLine> lines,
            Instant dispatchBy,
            int releasePriority,
            Instant committedAt) {

        public AllocationSnapshot {
            Objects.requireNonNull(allocationId, "Allocation ID is required");
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(ownerId, "Owner ID is required");
            Objects.requireNonNull(facilityId, "Facility ID is required");
            Objects.requireNonNull(lines, "Allocation lines are required");
            Objects.requireNonNull(dispatchBy, "Dispatch deadline is required");
            Objects.requireNonNull(committedAt, "Allocation commit time is required");
            lines = List.copyOf(lines);
            if (lines.isEmpty() || lines.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Allocation requires non-empty lines");
            }
            Set<UUID> moveIds = new HashSet<>();
            if (lines.stream().anyMatch(line -> !moveIds.add(line.moveId()))) {
                throw new IllegalArgumentException("Allocation requires unique move IDs");
            }
            if (releasePriority < 0 || releasePriority > 100) {
                throw new IllegalArgumentException("Release priority must be between 0 and 100");
            }
        }
    }

    record AllocationLine(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {

        public AllocationLine {
            Objects.requireNonNull(orderLineId, "Order line ID is required");
            Objects.requireNonNull(moveId, "Move ID is required");
            Objects.requireNonNull(sourceLocationId, "Source location ID is required");
            if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
                throw new IllegalArgumentException("Allocation line requires SKU and positive quantity");
            }
        }
    }

    /** Workflow 專用訊息，由 adapter 從 WMS 的同名 domain/integration event 映射而來。 */
    record ShipmentHandedOverToCarrier(UUID orderId, UUID shipmentId, Instant handedOverAt) {

        public ShipmentHandedOverToCarrier {
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(shipmentId, "Shipment ID is required");
            Objects.requireNonNull(handedOverAt, "Handover time is required");
        }
    }

    /** 人工、逾期政策或上游系統要求停止尚未離倉的履約流程。 */
    record CancellationRequest(UUID requestId, UUID orderId, Instant requestedAt, String reason) {

        public CancellationRequest {
            Objects.requireNonNull(requestId, "Cancellation request ID is required");
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(requestedAt, "Cancellation request time is required");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation reason is required");
            }
        }
    }

    enum CancellationRequestStatus {
        ACCEPTED,
        ALREADY_REQUESTED,
        ALREADY_CANCELLED,
        REJECTED
    }

    /** Update 的立即受理結果，不代表 WMS 或 Order cancellation 已完成，也不是 OrderCancelled fact。 */
    record CancellationRequestAck(CancellationRequestStatus status, UUID effectiveRequestId, String detail) {

        public CancellationRequestAck {
            Objects.requireNonNull(status, "Cancellation request status is required");
            Objects.requireNonNull(effectiveRequestId, "Effective cancellation request ID is required");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("Cancellation request detail is required");
            }
        }
    }

    /** Workflow 目前位於哪一段跨系統協調；不鏡像 Activity 的執行細節。 */
    enum Phase {
        /** Workflow 尚未進入履約流程；主要供啟動前的 Query 顯示。 */
        NOT_STARTED,

        /** 正在要求配貨，或等待可交給 WMS 的 committed allocation。 */
        ALLOCATION,

        /** 正在呼叫 WMS 冪等建立 Shipment。 */
        WMS_SHIPMENT_CREATION,

        /** WMS Shipment 已建立，等待實際交付承運商；取消命令可中斷此等待。 */
        SHIPMENT_HANDOVER,

        /** 已收到承運商交接事實，正在由 Stock context 完成 outbound movements。 */
        OUTBOUND_COMPLETION,

        /** 出庫 movements 已完成，正在由 Ordering context 記錄整單履約完成。 */
        ORDER_COMPLETION,

        /** 正在取得 WMS 取消決策、等待必要的最終結果，或提交 Ordering cancellation。 */
        CANCELLATION,

        /** Workflow 已產生不可再變動的最終 Outcome。 */
        FINISHED
    }

    /**
     * Workflow 對配貨邊界的 checkpoint，不是 Ordering 的 {@code OrderStatus} 複本。
     */
    enum AllocationCheckpointState {
        /** Workflow 尚未要求配貨。 */
        NOT_REQUESTED,

        /** 已要求配貨，仍未取得可交給 WMS 的 committed snapshot。 */
        WAITING_FOR_COMMITMENT,

        /** 已取得可交給 WMS 的 committed allocation snapshot。 */
        COMMITTED
    }

    /** 只有 Workflow 結束時才存在的結果；進行中的 State.outcome 為 {@code null}。 */
    enum Outcome {
        /** Shipment 已交付承運商，outbound movements 與 Order 終態皆已完成。 */
        FULFILLMENT_COMPLETED,

        /**
         * WMS 已安全停止／復原（若 Shipment 存在），且 Ordering 已提交取消。
         * Stock movement 的釋放仍可由 OrderCancelled Outbox event 非同步完成。
         */
        ORDER_CANCELLED
    }

    /** 取消協調狀態；與履約主線 Phase 分開，避免 cancellation concern 散落成多個欄位。 */
    enum CancellationState {
        NONE,
        REQUESTED,
        ORDER_CANCELLED,
        REJECTED
    }

    record State(
            UUID orderId,
            Phase phase,
            AllocationCheckpointState allocationCheckpoint,
            CancellationState cancellationState,
            UUID cancellationRequestId,
            Instant cancellationRequestedAt,
            Outcome outcome,
            UUID allocationId,
            UUID shipmentId,
            Instant cancelledAt,
            Instant updatedAt,
            String detail) {}

    record Result(
            UUID orderId, Outcome outcome, UUID allocationId, UUID shipmentId, Instant completedAt, String detail) {}
}
