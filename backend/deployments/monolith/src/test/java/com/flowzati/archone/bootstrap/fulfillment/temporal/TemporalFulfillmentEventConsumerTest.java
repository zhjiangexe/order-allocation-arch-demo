package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverForFulfillmentIntegrationEvent;
import com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalFulfillmentEventConsumerTest {

    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final OrderFulfillmentProcessWorkflow workflow = mock(OrderFulfillmentProcessWorkflow.class);
    private final TemporalFulfillmentEventConsumer consumer = new TemporalFulfillmentEventConsumer(workflowClient);

    @Test
    void mapsAllocationFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentProcessWorkflow.class, OrderFulfillmentProcessWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new AllocationCommittedForFulfillmentIntegrationEvent(
                UUID.randomUUID(),
                allocationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new AllocationCommittedForFulfillmentIntegrationEvent.AllocationLine(
                        UUID.randomUUID(), movementId, "SKU-1", UUID.randomUUID(), 3)),
                committedAt.plusSeconds(3600),
                80,
                committedAt);

        consumer.onAllocationCommitted(event);

        ArgumentCaptor<OrderFulfillmentProcessWorkflow.AllocationSnapshot> signal =
                ArgumentCaptor.forClass(OrderFulfillmentProcessWorkflow.AllocationSnapshot.class);
        verify(workflow).allocationCommitted(signal.capture());
        assertThat(signal.getValue().allocationId()).isEqualTo(allocationId);
        assertThat(signal.getValue().lines())
                .extracting(OrderFulfillmentProcessWorkflow.AllocationLine::moveId)
                .containsExactly(movementId);
    }

    @Test
    void mapsWmsHandoverFactToTheOrderWorkflowSignal() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant handedOverAt = Instant.parse("2026-08-19T10:00:00Z");
        when(workflowClient.newWorkflowStub(
                        OrderFulfillmentProcessWorkflow.class, OrderFulfillmentProcessWorkflow.workflowId(orderId)))
                .thenReturn(workflow);
        var event = new ShipmentHandedOverForFulfillmentIntegrationEvent(
                UUID.randomUUID(), shipmentId, UUID.randomUUID(), orderId, List.of(UUID.randomUUID()), handedOverAt);

        consumer.onShipmentHandedOver(event);

        verify(workflow)
                .shipmentHandedOverToCarrier(new OrderFulfillmentProcessWorkflow.ShipmentHandedOverToCarrier(
                        orderId, shipmentId, handedOverAt));
    }
}
