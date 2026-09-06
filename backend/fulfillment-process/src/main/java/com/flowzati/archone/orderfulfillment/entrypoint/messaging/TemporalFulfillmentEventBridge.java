package com.flowzati.archone.orderfulfillment.entrypoint.messaging;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/** 將 fulfillment integration events 轉成 Temporal Workflow start 或 signal。 */
public class TemporalFulfillmentEventBridge {

    private final WorkflowClient workflowClient;

    public TemporalFulfillmentEventBridge(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public void startWorkflow(OrderPlacedIntegrationEvent event) {
        OrderFulfillmentWorkflow workflow =
                workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.class, workflowOptions(event.getOrderId()));
        try {
            WorkflowClient.start(
                    workflow::execute, new OrderFulfillmentInput(event.getOrderId(), event.getReceivedAt()));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Kafka redelivery 與 Inbox retry 可能再次嘗試啟動；workflowId 保證同一張 Order 只有一條流程。
        }
    }

    static WorkflowOptions workflowOptions(java.util.UUID orderId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(OrderFulfillmentWorkflow.workflowId(orderId))
                .setWorkflowIdConflictPolicy(WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE)
                .build();
    }

    public void signalStockOperationAssigned(OrderAllocationCommittedIntegrationEvent event) {
        workflow(event.getOrderId())
                .stockOperationAssigned(new StockOperationAssignedInput(
                        event.getStockOperationId(),
                        event.getOrderId(),
                        event.getOwnerId(),
                        event.getFacilityId(),
                        event.getMoves().stream()
                                .map(line -> new AssignedStockMove(
                                        line.orderLineId(),
                                        line.moveId(),
                                        line.skuCode(),
                                        event.getSourceLocationId(),
                                        line.quantity()))
                                .toList(),
                        event.getDispatchBy(),
                        event.getReleasePriority(),
                        event.getAssignedAt()));
    }

    public void signalShipmentHandedOver(ShipmentHandedOverIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierInput(
                        event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }

    public void signalShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentCancelled(new ShipmentCancelledInput(
                        event.getOrderId(),
                        event.getShipmentId(),
                        event.getCancellationRequestId(),
                        event.getCancelledAt()));
    }

    private OrderFulfillmentWorkflow workflow(java.util.UUID orderId) {
        return workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
    }
}
