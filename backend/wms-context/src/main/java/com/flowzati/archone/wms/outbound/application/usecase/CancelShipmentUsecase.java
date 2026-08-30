package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.event.ShipmentCancelled;
import com.flowzati.archone.wms.outbound.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationState;
import org.springframework.transaction.annotation.Transactional;

public class CancelShipmentUsecase {

    private final ShipmentStore shipmentStore;
    private final ShipmentCancelledPublisher shipmentCancelledPublisher;
    private final BusinessClock appClock;

    public CancelShipmentUsecase(
            ShipmentStore shipmentStore,
            ShipmentCancelledPublisher shipmentCancelledPublisher,
            BusinessClock appClock) {
        this.shipmentStore = shipmentStore;
        this.shipmentCancelledPublisher = shipmentCancelledPublisher;
        this.appClock = appClock;
    }

    @Transactional
    public CancelShipmentStatus handle(CancelShipmentCommand command) {
        Shipment shipment = shipmentStore
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        CancelShipmentStatus status =
                shipment.cancel(command.requestId(), command.requestedAt(), command.reason(), appClock.instant());
        shipmentStore.save(shipment);
        if (status == CancelShipmentStatus.ACCEPTED
                && shipment.cancellationStateValue().orElse(null) == ShipmentCancellationState.COMPLETED) {
            shipmentCancelledPublisher.publish(ShipmentCancelled.from(shipment));
        }
        return status;
    }
}
