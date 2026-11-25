package com.flowzati.archone.fulfillment.infrastructure.temporal;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
import com.flowzati.archone.fulfillment.application.invocation.StartOrderFulfillmentCommand;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;

/** 以 Temporal Workflow 實作訂單履約流程：開始流程用 workflow start，各方事實用 signal。 */
public class TemporalOrderFulfillmentWorkflowAdapter implements OrderFulfillmentWorkflowPort {

    private final WorkflowClient workflowClient;

    public TemporalOrderFulfillmentWorkflowAdapter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public void start(StartOrderFulfillmentCommand command) {
        OrderFulfillmentWorkflow workflow =
                workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.class, workflowOptions(command.orderId()));
        try {
            WorkflowClient.start(
                    workflow::execute, new OrderFulfillmentInput(command.orderId(), command.orderReceivedAt()));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Kafka redelivery 與 Inbox retry 可能再次嘗試啟動；workflowId 保證同一張 Order 只有一條流程。
        }
    }

    static WorkflowOptions workflowOptions(UUID orderId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(OrderFulfillmentWorkflow.workflowId(orderId))
                .setWorkflowIdConflictPolicy(WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE)
                .build();
    }

    @Override
    public void stockOperationAssigned(RecordStockOperationAssignedCommand command) {
        workflow(command.orderId())
                .stockOperationAssigned(new StockOperationAssignedInput(
                        command.stockOperationId(),
                        command.orderId(),
                        command.ownerId(),
                        command.facilityId(),
                        command.moves().stream()
                                .map(line -> new AssignedStockMove(
                                        line.orderLineId(),
                                        line.moveId(),
                                        line.skuCode(),
                                        line.sourceLocationId(),
                                        line.quantity()))
                                .toList(),
                        command.dispatchBy(),
                        command.releasePriority(),
                        command.assignedAt()));
    }

    @Override
    public void shipmentHandedOver(RecordShipmentHandedOverCommand command) {
        workflow(command.orderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierInput(
                        command.orderId(), command.shipmentId(), command.handedOverAt()));
    }

    @Override
    public void shipmentCancelled(RecordShipmentCancelledCommand command) {
        workflow(command.orderId())
                .shipmentCancelled(new ShipmentCancelledInput(
                        command.orderId(),
                        command.shipmentId(),
                        command.cancellationRequestId(),
                        command.cancelledAt()));
    }

    private OrderFulfillmentWorkflow workflow(UUID orderId) {
        return workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
    }
}
