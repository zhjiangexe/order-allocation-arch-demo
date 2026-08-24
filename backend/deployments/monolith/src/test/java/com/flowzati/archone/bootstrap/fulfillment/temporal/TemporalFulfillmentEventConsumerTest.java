package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentCancelledSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalFulfillmentEventConsumerTest {

    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final OrderFulfillmentWorkflow workflow = mock(OrderFulfillmentWorkflow.class);
    private final TemporalFulfillmentEventConsumer consumer = new TemporalFulfillmentEventConsumer(workflowClient);

    @Test
    void preventsStartingAnotherFulfillmentWorkflowForTheSameOrder() {
        UUID orderId = UUID.randomUUID();

        WorkflowOptions options = TemporalFulfillmentEventConsumer.workflowOptions(orderId);

        assertThat(options.getWorkflowId()).isEqualTo(OrderFulfillmentWorkflow.workflowId(orderId));
        assertThat(options.getWorkflowIdConflictPolicy()).isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_FAIL);
        assertThat(options.getWorkflowIdReusePolicy()).isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
        assertThat(options.getTaskQueue()).isEqualTo(OrderFulfillmentWorkflow.TASK_QUEUE);
    }

    @Test
    void mapsAllocationFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                allocationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AllocationLine(
                        UUID.randomUUID(), movementId, "SKU-1", UUID.randomUUID(), 3)),
                committedAt.plusSeconds(3600),
                80,
                committedAt);

        consumer.onAllocationCommitted(event);

        ArgumentCaptor<AllocationSnapshot> signal = ArgumentCaptor.forClass(AllocationSnapshot.class);
        verify(workflow).allocationCommitted(signal.capture());
        assertThat(signal.getValue().allocationId()).isEqualTo(allocationId);
        assertThat(signal.getValue().lines())
                .extracting(AllocationSnapshotLine::moveId)
                .containsExactly(movementId);
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

        consumer.onShipmentHandedOver(event);

        verify(workflow)
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, handedOverAt));
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
                orderId,
                cancellationRequestId,
                requestedAt,
                "customer request",
                cancelledAt);

        consumer.onShipmentCancelled(event);

        verify(workflow)
                .shipmentCancelled(
                        new ShipmentCancelledSignal(orderId, shipmentId, cancellationRequestId, cancelledAt));
    }
}
