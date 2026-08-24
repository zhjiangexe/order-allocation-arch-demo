package com.flowzati.archone.wms.outbound.entrypoint.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.exception.ShipmentCancellationRequestConflictException;
import com.flowzati.archone.wms.outbound.domain.type.CancelShipmentStatus;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalWmsActivitiesAdapterTest {

    private final CreateShipmentUsecase createShipmentUsecase = mock(CreateShipmentUsecase.class);
    private final CancelShipmentUsecase cancelShipmentUsecase = mock(CancelShipmentUsecase.class);
    private final TemporalWmsActivitiesAdapter activities =
            new TemporalWmsActivitiesAdapter(createShipmentUsecase, cancelShipmentUsecase);

    @Test
    void mapsCommittedAllocationToTheSharedCreateShipmentUsecase() {
        UUID returnedShipmentId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant committedAt = Instant.parse("2026-08-19T10:00:00Z");
        var allocation = new AllocationSnapshot(
                allocationId,
                orderId,
                ownerId,
                facilityId,
                List.of(new AllocationSnapshotLine(orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                committedAt.plusSeconds(3600),
                80,
                committedAt);
        when(createShipmentUsecase.handle(any())).thenReturn(new CreateShipmentResult(returnedShipmentId));

        var receipt = activities.createShipment(new CreateShipmentActivityInput("process-1", allocation));

        assertThat(receipt.shipmentId()).isEqualTo(returnedShipmentId);
        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(createShipmentUsecase).handle(command.capture());
        assertThat(command.getValue().shipmentId()).isNotNull();
        assertThat(command.getValue().allocationId()).isEqualTo(allocationId);
        assertThat(command.getValue().lines())
                .extracting(CreateShipmentCommand.AllocationLine::moveId)
                .containsExactly(moveId);
    }

    @Test
    void acknowledgesTheCommandWithoutExposingWmsRecoveryState() {
        UUID requestId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-19T10:00:00Z");
        when(cancelShipmentUsecase.handle(any())).thenReturn(CancelShipmentStatus.ACCEPTED);

        activities.requestShipmentCancellation(new CancelShipmentActivityInput(
                "process-1", requestId, UUID.randomUUID(), shipmentId, requestedAt, "customer request"));

        verify(cancelShipmentUsecase)
                .handle(new CancelShipmentCommand(requestId, shipmentId, requestedAt, "customer request"));
    }

    @Test
    void marksCancellationRequestConflictsAsNonRetryable() {
        UUID requestId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-19T10:00:00Z");
        CancelShipmentCommand command =
                new CancelShipmentCommand(requestId, shipmentId, requestedAt, "customer request");
        when(cancelShipmentUsecase.handle(command))
                .thenThrow(new ShipmentCancellationRequestConflictException("different cancellation request"));

        assertThatThrownBy(() -> activities.requestShipmentCancellation(new CancelShipmentActivityInput(
                        "process-1", requestId, UUID.randomUUID(), shipmentId, requestedAt, "customer request")))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> assertThat(((ApplicationFailure) failure).isNonRetryable())
                        .isTrue());
    }
}
