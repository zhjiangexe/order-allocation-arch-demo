package com.flowzati.archone.orchestration.contract.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Objects;
import java.util.UUID;

/**
 * 從訂單可靠成立後開始，協調 Inventory、Ordering 與 WMS 的粗粒度履約主線。
 *
 * <p>Workflow 不擁有 Order、StockOperation 或 Shipment aggregate；它只保存跨 bounded context
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
    void execute(OrderFulfillmentInput input);

    /** 接收可交給 WMS 的 canonical assigned stock-operation snapshot。 */
    @SignalMethod(name = "stockOperationAssigned")
    void stockOperationAssigned(StockOperationAssignedInput assignment);

    /**
     * 接收 {@code ShipmentHandedOverToCarrierInput} 業務事實；Workflow 隨後要求 Stock context
     * 完成對應的 outbound movements。
     */
    @SignalMethod(name = "shipmentHandedOverToCarrier")
    void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierInput handover);

    /** 接收 WMS 已完成停止作業與必要 recovery 的 Shipment cancellation fact。 */
    @SignalMethod(name = "shipmentCancelled")
    void shipmentCancelled(ShipmentCancelledInput cancellation);

    /**
     * 外部入口提交「要求取消」命令，但不得先取消 Order。Update 只接受並記錄請求；WMS 安全判斷與
     * Order cancellation 由 {@link #execute(OrderFulfillmentInput)} 主線在同一條 Workflow execution 協調。
     */
    @UpdateMethod(name = "requestCancellation")
    CancellationRequestResult requestCancellation(CancellationRequestInput request);

    /** 在 Update 寫入 Workflow History 前拒絕無法屬於此流程的取消請求。 */
    @UpdateValidatorMethod(updateName = "requestCancellation")
    void validateCancellationRequest(CancellationRequestInput request);

    /** 提供 API／維運工具查詢，不取代各 bounded context 的 aggregate/read model。 */
    @QueryMethod(name = "state")
    OrderFulfillmentSnapshot state();
}
