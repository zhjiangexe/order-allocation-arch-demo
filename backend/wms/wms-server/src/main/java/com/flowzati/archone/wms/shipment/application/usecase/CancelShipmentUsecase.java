package com.flowzati.archone.wms.shipment.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancelled;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentCancellationState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelShipmentUsecase {

    private final ShipmentStore shipmentStore;
    private final ShipmentCancelledPublisher shipmentCancelledPublisher;
    private final ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher;
    private final BusinessClock appClock;

    public CancelShipmentUsecase(
            ShipmentStore shipmentStore,
            ShipmentCancelledPublisher shipmentCancelledPublisher,
            ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher,
            BusinessClock appClock) {
        this.shipmentStore = shipmentStore;
        this.shipmentCancelledPublisher = shipmentCancelledPublisher;
        this.shipmentCancellationCompletedPublisher = shipmentCancellationCompletedPublisher;
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
            shipmentCancellationCompletedPublisher.publish(
                    new ShipmentCancellationCompleted(shipment.id(), shipment.cancelledAt()));
            shipmentCancelledPublisher.publish(ShipmentCancelled.from(shipment));
        }
        return status;
    }
}
