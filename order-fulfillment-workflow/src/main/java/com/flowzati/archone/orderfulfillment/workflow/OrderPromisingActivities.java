package com.flowzati.archone.orderfulfillment.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 由 order-promising deployable 執行的 Activity contract。
 *
 * <p>Ordering 與 Stock 是不同 bounded context，但目前部署在同一個 process，故共用 task queue。
 * implementation 只負責轉接到完整且可冪等的 application use case，不直接操作 repository。
 */
@ActivityInterface
public interface OrderPromisingActivities {

    String TASK_QUEUE = "order-promising-activities";

    /**
     * 要求開始或冪等重送訂單配貨。返回只代表命令已執行；現有 use case 尚不能在 retry 後穩定
     * 重建結果，因此最終 committed snapshot 仍由 {@code allocationCommitted} Signal 回報。
     * BACKORDERED 留在 Order／Stock 的 domain model 與 read model，不是 Workflow checkpoint。
     */
    @ActivityMethod(name = "RequestOrderAllocation")
    void requestAllocation(RequestAllocation input);

    /**
     * WMS 已完成 custody 交接後，呼叫 Stock context 的出庫 transaction boundary，完成對應
     * movements 與實際庫存扣帳。
     */
    @ActivityMethod(name = "CompleteOutboundMovements")
    void completeOutboundMovements(CompleteOutboundMovements input);

    /**
     * 出庫 movements 已完成後，呼叫 Ordering context 將整張訂單冪等推進到 FULFILLED。
     */
    @ActivityMethod(name = "RecordOrderFulfillment")
    void recordOrderFulfillment(RecordOrderFulfillment input);

    /**
     * 執行 Ordering 的冪等取消 transaction。回傳值表示業務結果；技術性失敗仍以 exception
     * 表示並交由 Temporal retry。後續 Outbox event 仍負責通知 Stock 等其他 consumer。
     */
    @ActivityMethod(name = "CancelOrder")
    CancelOrderResult cancelOrder(CancelOrder input);

    record RequestAllocation(String processId, UUID orderId, Instant orderReceivedAt) {
        public RequestAllocation {
            requireIdentity(processId, orderId);
            Objects.requireNonNull(orderReceivedAt, "Order received time is required");
        }
    }

    record CompleteOutboundMovements(
            String processId,
            UUID orderId,
            UUID allocationId,
            UUID shipmentId,
            List<UUID> movementIds,
            Instant handedOverAt) {

        public CompleteOutboundMovements {
            requireIdentity(processId, orderId);
            Objects.requireNonNull(allocationId, "Allocation ID is required");
            Objects.requireNonNull(shipmentId, "Shipment ID is required");
            Objects.requireNonNull(movementIds, "Movement IDs are required");
            Objects.requireNonNull(handedOverAt, "Handover time is required");
            movementIds = List.copyOf(movementIds);
            if (movementIds.isEmpty() || movementIds.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("At least one movement ID is required");
            }
            Set<UUID> uniqueMovementIds = new HashSet<>(movementIds);
            if (uniqueMovementIds.size() != movementIds.size()) {
                throw new IllegalArgumentException("Movement IDs must be unique");
            }
        }
    }

    record RecordOrderFulfillment(String processId, UUID orderId, UUID shipmentId, Instant fulfilledAt) {

        public RecordOrderFulfillment {
            requireIdentity(processId, orderId);
            Objects.requireNonNull(shipmentId, "Shipment ID is required");
            Objects.requireNonNull(fulfilledAt, "Fulfilled time is required");
        }
    }

    record CancelOrder(String processId, UUID requestId, UUID orderId, Instant requestedAt, String reason) {

        public CancelOrder {
            requireIdentity(processId, orderId);
            Objects.requireNonNull(requestId, "Cancellation request ID is required");
            Objects.requireNonNull(requestedAt, "Cancellation request time is required");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation reason is required");
            }
        }
    }

    record CancelOrderResult(UUID orderId, CancelOrderStatus status) {
        public CancelOrderResult {
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(status, "Cancel order status is required");
        }
    }

    enum CancelOrderStatus {
        CANCELLED,
        ALREADY_CANCELLED,
        REJECTED
    }

    private static void requireIdentity(String processId, UUID orderId) {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(orderId, "Order ID is required");
    }
}
