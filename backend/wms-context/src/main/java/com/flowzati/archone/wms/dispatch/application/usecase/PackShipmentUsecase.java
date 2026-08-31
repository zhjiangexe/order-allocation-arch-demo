package com.flowzati.archone.wms.dispatch.application.usecase;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.invocation.PackShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class PackShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(PackShipmentUsecase.class);

    private final ShipmentStore shipmentStore;
    private final ShipmentDispatchStore shipmentDispatchStore;
    private final ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher;

    public PackShipmentUsecase(
            ShipmentStore shipmentStore,
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        this.shipmentStore = shipmentStore;
        this.shipmentDispatchStore = shipmentDispatchStore;
        this.shipmentDispatchStatusChangedPublisher = shipmentDispatchStatusChangedPublisher;
    }

    @Transactional
    public void handle(PackShipmentCommand command) {
        Shipment shipment = required(command.shipmentId());
        if (shipmentDispatchStore.findByShipmentId(shipment.id()).isPresent()) {
            return;
        }
        if (shipment.status() != ShipmentStatus.PICKED) {
            throw new IllegalStateException(
                    "Only a picked Shipment can enter ShipmentDispatch, was " + shipment.status());
        }
        ShipmentDispatch shipmentDispatch =
                ShipmentDispatch.pack(IdGenerator.nextId(), shipment.id(), command.packedAt());
        shipmentDispatchStore.save(shipmentDispatch);
        shipmentDispatchStatusChangedPublisher.publish(new ShipmentDispatchStatusChanged(
                shipmentDispatch.id(), shipment.id(), shipmentDispatch.status(), command.packedAt()));
        log.info(
                "WMS shipment dispatch packed: shipmentDispatchId={}, shipmentId={}",
                shipmentDispatch.id(),
                shipment.id());
    }

    private Shipment required(java.util.UUID shipmentId) {
        return shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
