package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentCancelledSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshotLine;
import com.flowzati.archone.wms.shipment.application.service.LegacyAllocationPickingResolver;
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
    private final LegacyAllocationPickingResolver legacyPickingResolver = mock(LegacyAllocationPickingResolver.class);
    private final TemporalFulfillmentEventConsumer consumer =
            new TemporalFulfillmentEventConsumer(workflowClient, legacyPickingResolver);

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
    void mapsPickingAssignmentFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        UUID.randomUUID(),
                        movementId,
                        "SKU-1",
                        3,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);

        consumer.onPickingAssigned(event);

        ArgumentCaptor<StockOperationAssignmentSnapshot> signal =
                ArgumentCaptor.forClass(StockOperationAssignmentSnapshot.class);
        verify(workflow).stockOperationAssigned(signal.capture());
        assertThat(signal.getValue().stockOperationId()).isEqualTo(stockOperationId);
        assertThat(signal.getValue().moves())
                .extracting(StockOperationAssignmentSnapshotLine::moveId)
                .containsExactly(movementId);
    }

    @Test
    void mapsCanonicalStockOperationFactToTheCanonicalWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(
                        new com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent
                                .AssignedMove(
                                UUID.randomUUID(),
                                movementId,
                                "SKU-1",
                                3,
                                List.of(new com.flowzati.archone.contracts.promising.v3
                                        .OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);

        consumer.onStockOperationAssigned(event);

        ArgumentCaptor<StockOperationAssignmentSnapshot> signal =
                ArgumentCaptor.forClass(StockOperationAssignmentSnapshot.class);
        verify(workflow).stockOperationAssigned(signal.capture());
        assertThat(signal.getValue().stockOperationId()).isEqualTo(stockOperationId);
        assertThat(signal.getValue().moves())
                .extracting(StockOperationAssignmentSnapshotLine::moveId)
                .containsExactly(movementId);
    }

    @Test
    void resolvesRetainedLegacyAllocationIdentityBeforeSignallingTheWorkflow() {
        UUID orderId = UUID.randomUUID();
        UUID legacyAllocationId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        when(legacyPickingResolver.resolve(legacyAllocationId, List.of(moveId))).thenReturn(stockOperationId);
        var event =
                new com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent(
                        UUID.randomUUID(),
                        legacyAllocationId,
                        UUID.randomUUID(),
                        orderId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of(new com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent
                                .AllocationLine(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                moveId,
                                "SKU-1",
                                UUID.randomUUID(),
                                3,
                                List.of(new com.flowzati.archone.contracts.promising.v1
                                        .OrderAllocationCommittedIntegrationEvent.AllocationSlice(
                                        UUID.randomUUID(), UUID.randomUUID(), 3)))),
                        committedAt.plusSeconds(3600),
                        80,
                        committedAt);

        consumer.onLegacyAllocationCommitted(event);

        ArgumentCaptor<StockOperationAssignmentSnapshot> signal =
                ArgumentCaptor.forClass(StockOperationAssignmentSnapshot.class);
        verify(workflow).stockOperationAssigned(signal.capture());
        verify(legacyPickingResolver).resolve(legacyAllocationId, List.of(moveId));
        assertThat(signal.getValue().stockOperationId()).isEqualTo(stockOperationId);
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
                UUID.randomUUID(),
                shipmentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                List.of(UUID.randomUUID()),
                handedOverAt);

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
                UUID.randomUUID(),
                UUID.randomUUID(),
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
