package com.flowzati.archone.bootstrap.fulfillment.temporal;

import com.flowzati.archone.orderfulfillment.workflow.WmsActivities;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.type.CancellationOutcome;
import com.flowzati.archone.wms.shared.application.IdGenerator;

/** Temporal Activity 到 WMS application use case 的薄轉接層。 */
public class WmsActivitiesImpl implements WmsActivities {

    private final CreateShipmentUsecase createShipmentUsecase;
    private final CancelShipmentUsecase cancelShipmentUsecase;
    private final IdGenerator idGenerator;

    public WmsActivitiesImpl(
            CreateShipmentUsecase createShipmentUsecase,
            CancelShipmentUsecase cancelShipmentUsecase,
            IdGenerator idGenerator) {
        this.createShipmentUsecase = createShipmentUsecase;
        this.cancelShipmentUsecase = cancelShipmentUsecase;
        this.idGenerator = idGenerator;
    }

    @Override
    public ShipmentCreationReceipt createShipment(CreateShipment input) {
        var allocation = input.allocation();
        CreateShipmentResult result = createShipmentUsecase.handle(new CreateShipmentCommand(
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
        return new ShipmentCreationReceipt(result.shipmentId());
    }

    @Override
    public ShipmentCancellationDecisionStatus cancelShipment(CancelShipment input) {
        CancellationOutcome outcome = cancelShipmentUsecase.handle(
                new CancelShipmentCommand(input.requestId().toString(), input.shipmentId(), input.requestedAt()));
        return switch (outcome) {
            case CANCELLED, ALREADY_CANCELLED -> ShipmentCancellationDecisionStatus.CANCELLED;
            case PUTBACK_REQUIRED, REJECTED_AFTER_HANDOVER -> ShipmentCancellationDecisionStatus.REJECTED;
        };
    }
}
