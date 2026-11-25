package com.flowzati.archone.fulfillment.infrastructure.temporal;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.invocation.AssignedStockMoveLine;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
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

class TemporalOrderFulfillmentWorkflowAdapterTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-08-19T10:00:00Z");

    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
    private final TemporalOrderFulfillmentWorkflowAdapter adapter =
            new TemporalOrderFulfillmentWorkflowAdapter(workflowClient);

    @Test
    void preventsStartingAnotherFulfillmentWorkflowForTheSameOrder() {
        WorkflowOptions options = TemporalOrderFulfillmentWorkflowAdapter.workflowOptions(ORDER_ID);

        assertThat(options.getWorkflowId()).isEqualTo(OrderFulfillmentWorkflow.workflowId(ORDER_ID));
        assertThat(options.getWorkflowIdConflictPolicy()).isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_FAIL);
        assertThat(options.getWorkflowIdReusePolicy()).isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
        assertThat(options.getTaskQueue()).isEqualTo(OrderFulfillmentWorkflow.TASK_QUEUE);
    }

    @Test
    void signalsStockOperationAssignment() {
        givenWorkflow();
        UUID stockOperationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();

        adapter.stockOperationAssigned(new RecordStockOperationAssignedCommand(
                stockOperationId,
                ORDER_ID,
                ownerId,
                facilityId,
                List.of(new AssignedStockMoveLine(orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                AT.plusSeconds(3600),
                80,
                AT));

        verify(workflow)
                .stockOperationAssigned(new StockOperationAssignedInput(
                        stockOperationId,
                        ORDER_ID,
                        ownerId,
                        facilityId,
                        List.of(new AssignedStockMove(orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                        AT.plusSeconds(3600),
                        80,
                        AT));
    }

    @Test
    void signalsShipmentHandover() {
        givenWorkflow();
        UUID shipmentId = UUID.randomUUID();

        adapter.shipmentHandedOver(new RecordShipmentHandedOverCommand(ORDER_ID, shipmentId, AT));

        verify(workflow).shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierInput(ORDER_ID, shipmentId, AT));
    }

    @Test
    void signalsShipmentCancellation() {
        givenWorkflow();
        UUID shipmentId = UUID.randomUUID();
        UUID cancellationRequestId = UUID.randomUUID();

        adapter.shipmentCancelled(new RecordShipmentCancelledCommand(ORDER_ID, shipmentId, cancellationRequestId, AT));

        verify(workflow).shipmentCancelled(new ShipmentCancelledInput(ORDER_ID, shipmentId, cancellationRequestId, AT));
    }

    private void givenWorkflow() {
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(ORDER_ID)))
                .thenReturn(workflow);
    }
}
