package com.flowzati.archone.wms.shipment.entrypoint.temporal;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.simulation.SimulationUtil;
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
        CreateShipmentResult result = createShipmentUsecase.handle(new CreateShipmentCommand(
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
        SimulationUtil.sleep(3_000);
        return new ReleaseToWarehouseActivityResult(result.shipmentId());
    }

    @Override
    public CancelShipmentActivityStatus requestShipmentCancellation(CancelShipmentActivityInput input) {
        CancelShipmentStatus status = cancelShipmentUsecase.handle(
                new CancelShipmentCommand(input.requestId(), input.shipmentId(), input.requestedAt(), input.reason()));
        SimulationUtil.sleep(3_000);
        return status == CancelShipmentStatus.REJECTED
                ? CancelShipmentActivityStatus.REJECTED
                : CancelShipmentActivityStatus.ACCEPTED;
    }
}
