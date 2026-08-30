package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.PackShipmentCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class PackShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(PackShipmentUsecase.class);

    private final ShipmentStore shipmentStore;

    public PackShipmentUsecase(ShipmentStore shipmentStore) {
        this.shipmentStore = shipmentStore;
    }

    @Transactional
    public void handle(PackShipmentCommand command) {
        Shipment shipment = required(command.shipmentId());
        shipment.pack(command.packedAt());
        shipmentStore.save(shipment);
        log.info("WMS shipment packed: shipmentId={}, status={}", shipment.id(), shipment.status());
    }

    private Shipment required(java.util.UUID shipmentId) {
        return shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
