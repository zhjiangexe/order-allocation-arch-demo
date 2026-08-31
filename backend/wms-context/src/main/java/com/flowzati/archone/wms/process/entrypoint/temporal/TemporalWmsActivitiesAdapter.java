package com.flowzati.archone.wms.process.entrypoint.temporal;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.exception.ShipmentCancellationRequestConflictException;
import com.flowzati.archone.wms.shipment.domain.exception.ShipmentStockOperationSnapshotConflictException;
import io.temporal.failure.ApplicationFailure;

/** Temporal Activity contract 到 WMS application use cases 的 inbound adapter。 */
public final class TemporalWmsActivitiesAdapter implements WmsActivities {

    private final CreateShipmentUsecase createShipmentUsecase;
    private final CancelShipmentUsecase cancelShipmentUsecase;

    public TemporalWmsActivitiesAdapter(
            CreateShipmentUsecase createShipmentUsecase, CancelShipmentUsecase cancelShipmentUsecase) {
        this.createShipmentUsecase = createShipmentUsecase;
        this.cancelShipmentUsecase = cancelShipmentUsecase;
    }

    @Override
    public CreateShipmentActivityResult createShipment(CreateShipmentActivityInput input) {
        var assignment = input.assignment();
        CreateShipmentResult result;
        try {
            result = createShipmentUsecase.handle(new CreateShipmentCommand(
                    IdGenerator.nextId(),
                    assignment.stockOperationId(),
                    assignment.orderId(),
                    assignment.ownerId(),
                    assignment.facilityId(),
                    assignment.moves().stream()
                            .map(line -> new CreateShipmentCommand.MovementLine(
                                    line.orderLineId(),
                                    line.moveId(),
                                    line.skuCode(),
                                    line.sourceLocationId(),
                                    line.quantity()))
                            .toList(),
                    assignment.dispatchBy(),
                    assignment.releasePriority(),
                    assignment.assignedAt()));
        } catch (ShipmentStockOperationSnapshotConflictException exception) {
            throw nonRetryable(exception, "WMS_SHIPMENT_STOCK_OPERATION_SNAPSHOT_CONFLICT");
        }
        return new CreateShipmentActivityResult(result.shipmentId());
    }

    @Override
    public void requestShipmentCancellation(CancelShipmentActivityInput input) {
        try {
            cancelShipmentUsecase.handle(new CancelShipmentCommand(
                    input.requestId(), input.shipmentId(), input.requestedAt(), input.reason()));
        } catch (ShipmentCancellationRequestConflictException exception) {
            throw nonRetryable(exception, "WMS_SHIPMENT_CANCELLATION_REQUEST_CONFLICT");
        }
    }

    private static ApplicationFailure nonRetryable(RuntimeException exception, String type) {
        return ApplicationFailure.newNonRetryableFailure(exception.getMessage(), type);
    }
}
