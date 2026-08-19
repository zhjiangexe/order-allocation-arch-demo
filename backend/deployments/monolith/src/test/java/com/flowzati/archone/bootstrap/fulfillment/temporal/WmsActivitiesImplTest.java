package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.type.CancellationOutcome;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WmsActivitiesImplTest {

    private final CreateShipmentUsecase createShipmentUsecase = mock(CreateShipmentUsecase.class);
    private final CancelShipmentUsecase cancelShipmentUsecase = mock(CancelShipmentUsecase.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final WmsActivitiesImpl activities =
            new WmsActivitiesImpl(createShipmentUsecase, cancelShipmentUsecase, idGenerator);

    @Test
    void mapsCommittedAllocationToTheSharedCreateShipmentUsecase() {
        UUID generatedShipmentId = UUID.randomUUID();
        UUID returnedShipmentId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-19T10:00:00Z");
        var allocation = new OrderFulfillmentProcessWorkflow.AllocationSnapshot(
                allocationId,
                orderId,
                ownerId,
                facilityId,
                List.of(new OrderFulfillmentProcessWorkflow.AllocationLine(
                        orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                committedAt.plusSeconds(3600),
                80,
                committedAt);
        when(idGenerator.nextId()).thenReturn(generatedShipmentId);
        when(createShipmentUsecase.handle(any())).thenReturn(new CreateShipmentResult(returnedShipmentId));

        var receipt = activities.createShipment(new WmsActivities.CreateShipment("process-1", allocation));

        assertThat(receipt.shipmentId()).isEqualTo(returnedShipmentId);
        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(createShipmentUsecase).handle(command.capture());
        assertThat(command.getValue().shipmentId()).isEqualTo(generatedShipmentId);
        assertThat(command.getValue().allocationId()).isEqualTo(allocationId);
        assertThat(command.getValue().lines())
                .extracting(CreateShipmentCommand.AllocationLine::moveId)
                .containsExactly(moveId);
    }

    @Test
    void treatsPutbackRequiredAsARejectedSynchronousCancellation() {
        UUID requestId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(cancelShipmentUsecase.handle(any())).thenReturn(CancellationOutcome.PUTBACK_REQUIRED);

        var status = activities.cancelShipment(new WmsActivities.CancelShipment(
                "process-1", requestId, UUID.randomUUID(), shipmentId, requestedAt, "customer request"));

        assertThat(status).isEqualTo(WmsActivities.ShipmentCancellationDecisionStatus.REJECTED);
        verify(cancelShipmentUsecase).handle(new CancelShipmentCommand(requestId.toString(), shipmentId, requestedAt));
    }
}
