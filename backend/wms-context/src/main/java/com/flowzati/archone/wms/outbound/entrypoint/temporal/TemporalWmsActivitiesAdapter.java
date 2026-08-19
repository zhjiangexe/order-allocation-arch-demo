package com.flowzati.archone.wms.outbound.entrypoint.temporal;

import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.exception.ShipmentAllocationSnapshotConflictException;
import com.flowzati.archone.wms.outbound.domain.exception.ShipmentCancellationRequestConflictException;
import com.flowzati.archone.wms.outbound.domain.type.CancellationOutcome;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import io.temporal.failure.ApplicationFailure;

/** Temporal Activity contract 到 WMS application use cases 的 inbound adapter。 */
public final class TemporalWmsActivitiesAdapter implements WmsActivities {

    private final CreateShipmentUsecase createShipmentUsecase;
    private final CancelShipmentUsecase cancelShipmentUsecase;
    private final IdGenerator idGenerator;

    public TemporalWmsActivitiesAdapter(
            CreateShipmentUsecase createShipmentUsecase,
            CancelShipmentUsecase cancelShipmentUsecase,
            IdGenerator idGenerator) {
        this.createShipmentUsecase = createShipmentUsecase;
        this.cancelShipmentUsecase = cancelShipmentUsecase;
        this.idGenerator = idGenerator;
    }

    @Override
    public CreateShipmentActivityResult createShipment(CreateShipmentActivityInput input) {
        var allocation = input.allocation();
        CreateShipmentResult result;
        try {
            result = createShipmentUsecase.handle(new CreateShipmentCommand(
                    idGenerator.nextId(),
                    allocation.allocationId(),
                    allocation.orderId(),
                    allocation.ownerId(),
                    allocation.facilityId(),
                    allocation.lines().stream()
                            .map(line -> new CreateShipmentCommand.AllocationLine(
                                    line.orderLineId(),
                                    line.moveId(),
                                    line.skuCode(),
                                    line.sourceLocationId(),
                                    line.quantity()))
                            .toList(),
                    allocation.dispatchBy(),
                    allocation.releasePriority(),
                    allocation.committedAt()));
        } catch (ShipmentAllocationSnapshotConflictException exception) {
            throw nonRetryable(exception, "WMS_SHIPMENT_ALLOCATION_SNAPSHOT_CONFLICT");
        }
        return new CreateShipmentActivityResult(result.shipmentId());
    }

    @Override
    public CancelShipmentActivityStatus cancelShipment(CancelShipmentActivityInput input) {
        CancellationOutcome outcome;
        try {
            outcome = cancelShipmentUsecase.handle(
                    new CancelShipmentCommand(input.requestId().toString(), input.shipmentId(), input.requestedAt()));
        } catch (ShipmentCancellationRequestConflictException exception) {
            throw nonRetryable(exception, "WMS_SHIPMENT_CANCELLATION_REQUEST_CONFLICT");
        }
        return switch (outcome) {
            case CANCELLED, ALREADY_CANCELLED -> CancelShipmentActivityStatus.CANCELLED;
            case PUTBACK_REQUIRED, REJECTED_AFTER_HANDOVER -> CancelShipmentActivityStatus.REJECTED;
        };
    }

    private static ApplicationFailure nonRetryable(RuntimeException exception, String type) {
        return ApplicationFailure.newNonRetryableFailure(exception.getMessage(), type);
    }
}
