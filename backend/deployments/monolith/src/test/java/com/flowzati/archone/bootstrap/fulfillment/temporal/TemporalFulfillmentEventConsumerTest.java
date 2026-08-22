package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import io.temporal.client.WorkflowClient;
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
}
