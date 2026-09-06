package com.flowzati.archone.wms.shipment.entrypoint.temporal;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.BusinessException;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityResult;
import com.flowzati.archone.orchestration.contract.activity.wms.ShipmentActivities;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import io.temporal.failure.ApplicationFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal Shipment Activity contract 到 WMS Shipment application use cases 的 inbound adapter。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public final class TemporalShipmentActivitiesAdapter implements ShipmentActivities {

    private final CreateShipmentUsecase createShipmentUsecase;
    private final CancelShipmentUsecase cancelShipmentUsecase;

    public TemporalShipmentActivitiesAdapter(
            CreateShipmentUsecase createShipmentUsecase, CancelShipmentUsecase cancelShipmentUsecase) {
        this.createShipmentUsecase = createShipmentUsecase;
        this.cancelShipmentUsecase = cancelShipmentUsecase;
    }

    @Override
    public ReleaseToWarehouseActivityResult releaseToWarehouse(ReleaseToWarehouseActivityInput input) {
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
        } catch (ApplicationConflictException exception) {
            throw nonRetryable(exception);
        }
        return new ReleaseToWarehouseActivityResult(result.shipmentId());
    }

    @Override
    public CancelShipmentActivityStatus requestShipmentCancellation(CancelShipmentActivityInput input) {
        try {
            CancelShipmentStatus status = cancelShipmentUsecase.handle(new CancelShipmentCommand(
                    input.requestId(), input.shipmentId(), input.requestedAt(), input.reason()));
            return status == CancelShipmentStatus.REJECTED
                    ? CancelShipmentActivityStatus.REJECTED
                    : CancelShipmentActivityStatus.ACCEPTED;
        } catch (DomainConflictException exception) {
            throw nonRetryable(exception);
        }
    }

    private static ApplicationFailure nonRetryable(BusinessException exception) {
        return ApplicationFailure.newNonRetryableFailure(
                exception.getMessage(), exception.errorCode().value());
    }
}
