package com.flowzati.archone.orderfulfillment.contract.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Objects;
import java.util.UUID;

/**
 * 從訂單可靠成立後開始，協調 Order Promising 與 WMS 的粗粒度履約主線。
 *
 * <p>Workflow 不擁有 Order、Allocation 或 Shipment aggregate；它只保存跨 bounded context
 * checkpoint。各 Signal 由 integration adapter 將既有的 Integration Event 映射而來。
 */
@WorkflowInterface
public interface OrderFulfillmentWorkflow {

    String WORKFLOW_TYPE = "OrderFulfillmentWorkflow";
    String WORKFLOW_ID_PREFIX = "order-fulfillment/";
    String TASK_QUEUE = "order-fulfillment-workflows";

    static String workflowId(UUID orderId) {
        return WORKFLOW_ID_PREFIX + Objects.requireNonNull(orderId, "Order ID is required");
    }

    /** 啟動一張訂單唯一的長期履約協調流程。 */
    @WorkflowMethod(name = WORKFLOW_TYPE)
    OrderFulfillmentWorkflowResult execute(OrderFulfillmentWorkflowInput input);

    /** 接收可交給 WMS 的最終 committed allocation snapshot。 */
    @SignalMethod(name = "allocationCommitted")
    void allocationCommitted(AllocationSnapshot allocation);

    /**
     * 接收 {@code ShipmentHandedOverToCarrierSignal} 業務事實；Workflow 隨後要求 Stock context
     * 完成對應的 outbound movements。
     */
    @SignalMethod(name = "shipmentHandedOverToCarrier")
    void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierSignal handover);

    /**
     * 外部入口提交「要求取消」命令，但不得先取消 Order。Update 只接受並記錄請求；WMS 安全判斷與
     * Order cancellation 由 {@link #execute(OrderFulfillmentWorkflowInput)} 主線在同一條 Workflow execution 協調。
     */
    @UpdateMethod(name = "requestCancellation")
    CancellationRequestAcknowledgement requestCancellation(CancellationRequest request);

    /** 在 Update 寫入 Workflow History 前拒絕無法屬於此流程的取消請求。 */
    @UpdateValidatorMethod(updateName = "requestCancellation")
    void validateCancellationRequest(CancellationRequest request);

    /** 提供 API／維運工具查詢，不取代各 bounded context 的 aggregate/read model。 */
    @QueryMethod(name = "state")
    OrderFulfillmentWorkflowSnapshot state();
}
