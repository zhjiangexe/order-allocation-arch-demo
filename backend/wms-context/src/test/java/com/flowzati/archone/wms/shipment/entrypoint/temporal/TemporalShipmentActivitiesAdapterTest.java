package com.flowzati.archone.wms.shipment.entrypoint.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.wms.shipment.application.exception.ShipmentApplicationErrorCode;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.exception.ShipmentErrorCode;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class TemporalShipmentActivitiesAdapterTest {

    @ParameterizedTest
    @EnumSource(CancelShipmentStatus.class)
    void exposesRejectionAndAcknowledgesBothNewAndRepeatedAcceptedCommands(CancelShipmentStatus status) {
        when(cancelShipmentUsecase.handle(any())).thenReturn(status);

        var result = activities.requestShipmentCancellation(new CancelShipmentActivityInput(
                "process-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-08-19T10:00:00Z"),
                "Customer request"));

        assertThat(result)
                .isEqualTo(
                        status == CancelShipmentStatus.REJECTED
                                ? CancelShipmentActivityStatus.REJECTED
                                : CancelShipmentActivityStatus.ACCEPTED);
    }

    private final CreateShipmentUsecase createShipmentUsecase = mock(CreateShipmentUsecase.class);
    private final CancelShipmentUsecase cancelShipmentUsecase = mock(CancelShipmentUsecase.class);
    private final TemporalShipmentActivitiesAdapter activities =
            new TemporalShipmentActivitiesAdapter(createShipmentUsecase, cancelShipmentUsecase);

    @Test
    void mapsAssignedStockOperationToTheSharedCreateShipmentUsecase() {
        UUID returnedShipmentId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-19T10:00:00Z");
        var assignment = new StockOperationAssignedInput(
                stockOperationId,
                orderId,
                ownerId,
                facilityId,
                List.of(new AssignedStockMove(orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);
        when(createShipmentUsecase.handle(any())).thenReturn(new CreateShipmentResult(returnedShipmentId));

        var receipt = activities.releaseToWarehouse(new ReleaseToWarehouseActivityInput("process-1", assignment));

        assertThat(receipt.shipmentId()).isEqualTo(returnedShipmentId);
        ArgumentCaptor<CreateShipmentCommand> command = ArgumentCaptor.forClass(CreateShipmentCommand.class);
        verify(createShipmentUsecase).handle(command.capture());
        assertThat(command.getValue().shipmentId()).isNotNull();
        assertThat(command.getValue())
                .isEqualTo(new CreateShipmentCommand(
                        command.getValue().shipmentId(),
                        stockOperationId,
                        orderId,
                        ownerId,
                        facilityId,
                        List.of(new CreateShipmentCommand.MovementLine(
                                orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                        assignedAt.plusSeconds(3600),
                        80,
                        assignedAt));
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
                .thenThrow(new DomainConflictException(
                        ShipmentErrorCode.CANCELLATION_REQUEST_CONFLICT, "different cancellation request"));

        assertThatThrownBy(() -> activities.requestShipmentCancellation(new CancelShipmentActivityInput(
                        "process-1", requestId, UUID.randomUUID(), shipmentId, requestedAt, "customer request")))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getType()).isEqualTo(ShipmentErrorCode.CANCELLATION_REQUEST_CONFLICT.value());
                });
    }

    @Test
    void marksShipmentSnapshotConflictsAsNonRetryable() {
        Instant assignedAt = Instant.parse("2026-08-19T10:00:00Z");
        var assignment = new StockOperationAssignedInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new AssignedStockMove(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 3)),
                assignedAt.plusSeconds(3600),
                80,
                assignedAt);
        when(createShipmentUsecase.handle(any()))
                .thenThrow(new ApplicationConflictException(
                        ShipmentApplicationErrorCode.STOCK_OPERATION_SNAPSHOT_CONFLICT,
                        "different assignment snapshot"));

        assertThatThrownBy(() ->
                        activities.releaseToWarehouse(new ReleaseToWarehouseActivityInput("process-1", assignment)))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getType())
                            .isEqualTo(ShipmentApplicationErrorCode.STOCK_OPERATION_SNAPSHOT_CONFLICT.value());
                });
    }
}
