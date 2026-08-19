package com.flowzati.archone.orderfulfillment.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** WMS worker 擁有的 Activity contract；不在 Activity 裡等待 Kafka reply。 */
@ActivityInterface
public interface WmsActivities {

    String TASK_QUEUE = "wms-activities";

    /**
     * 以 committed allocation snapshot 冪等建立 Shipment，並在 transaction 提交後回傳建單
     * receipt。implementation 必須保證 receipt 非 null；技術失敗直接拋出交給 Temporal retry。
     */
    @ActivityMethod(name = "CreateWmsShipment")
    ShipmentCreationReceipt createShipment(CreateShipment input);

    /**
     * 冪等要求 WMS 取消 Shipment，並等待 WMS 回傳最終決策。WMS 必須在此 Activity 完成前判斷
     * Shipment 是否仍可安全取消；Workflow 不等待後續的停止／putback Signal。
     */
    @ActivityMethod(name = "CancelWmsShipment")
    ShipmentCancellationDecisionStatus cancelShipment(CancelShipment input);

    record CreateShipment(String processId, OrderFulfillmentProcessWorkflow.AllocationSnapshot allocation) {

        public CreateShipment {
            if (processId == null || processId.isBlank()) {
                throw new IllegalArgumentException("Process ID is required");
            }
            Objects.requireNonNull(allocation, "Allocation snapshot is required");
        }
    }

    /** 只證明 WMS 建單 checkpoint 已提交，不代表 Pick／Pack／Stage 或 carrier handover 完成。 */
    record ShipmentCreationReceipt(UUID shipmentId) {
        public ShipmentCreationReceipt {
            Objects.requireNonNull(shipmentId, "Shipment ID is required");
        }
    }

    record CancelShipment(
            String processId, UUID requestId, UUID orderId, UUID shipmentId, Instant requestedAt, String reason) {

        public CancelShipment {
            if (processId == null || processId.isBlank()) {
                throw new IllegalArgumentException("Process ID is required");
            }
            Objects.requireNonNull(requestId, "Cancellation request ID is required");
            Objects.requireNonNull(orderId, "Order ID is required");
            Objects.requireNonNull(shipmentId, "Shipment ID is required");
            Objects.requireNonNull(requestedAt, "Cancellation request time is required");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation reason is required");
            }
        }
    }

    enum ShipmentCancellationDecisionStatus {
        /** Shipment 已安全取消，不需要再等待 WMS fact。 */
        CANCELLED,

        /** WMS 已開始不可安全中止的履約作業，無法取消 Shipment。 */
        REJECTED
    }
}
