package com.flowzati.archone.orderfulfillment.entrypoint.messaging;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalFulfillmentEventBridgeTest {

    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
    private final TemporalFulfillmentEventBridge bridge = new TemporalFulfillmentEventBridge(workflowClient);

    @Test
    void preventsStartingAnotherFulfillmentWorkflowForTheSameOrder() {
        UUID orderId = UUID.randomUUID();

        WorkflowOptions options = TemporalFulfillmentEventBridge.workflowOptions(orderId);

        assertThat(options.getWorkflowId()).isEqualTo(OrderFulfillmentWorkflow.workflowId(orderId));
        assertThat(options.getWorkflowIdConflictPolicy()).isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_FAIL);
        assertThat(options.getWorkflowIdReusePolicy()).isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
        assertThat(options.getTaskQueue()).isEqualTo(OrderFulfillmentWorkflow.TASK_QUEUE);
    }

    @Test
    void mapsStockOperationAssignmentFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                orderId,
                ownerId,
                facilityId,
                UUID.randomUUID(),
                sourceLocationId,
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        orderLineId,
                        movementId,
                        "SKU-1",
                        3,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);

        bridge.signalStockOperationAssigned(event);

        ArgumentCaptor<StockOperationAssignedInput> signal = ArgumentCaptor.forClass(StockOperationAssignedInput.class);
        verify(workflow).stockOperationAssigned(signal.capture());
        assertThat(signal.getValue())
                .isEqualTo(new StockOperationAssignedInput(
                        stockOperationId,
                        orderId,
                        ownerId,
                        facilityId,
                        List.of(new AssignedStockMove(orderLineId, movementId, "SKU-1", sourceLocationId, 3)),
                        assignedAt.plusSeconds(3600),
                        80,
                        assignedAt));
    }

    @Test
    void mapsWmsHandoverFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant handedOverAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new ShipmentHandedOverIntegrationEvent(
                UUID.randomUUID(), shipmentId, UUID.randomUUID(), orderId, List.of(UUID.randomUUID()), handedOverAt);

        bridge.signalShipmentHandedOver(event);

        verify(workflow)
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierInput(orderId, shipmentId, handedOverAt));
    }

    @Test
    void mapsWmsCancellationFactToItsConcreteWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID cancellationRequestId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-19T10:00:00Z");
        Instant cancelledAt = requestedAt.plusSeconds(30);
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new ShipmentCancelledIntegrationEvent(
                UUID.randomUUID(),
                shipmentId,
                UUID.randomUUID(),
                orderId,
                cancellationRequestId,
                requestedAt,
                "customer request",
                cancelledAt);

        bridge.signalShipmentCancelled(event);

        verify(workflow)
                .shipmentCancelled(new ShipmentCancelledInput(orderId, shipmentId, cancellationRequestId, cancelledAt));
    }
}
