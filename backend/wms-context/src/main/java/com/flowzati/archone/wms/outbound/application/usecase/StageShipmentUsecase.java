package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.StageShipmentCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class StageShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(StageShipmentUsecase.class);

    private final ShipmentStore shipmentStore;

    public StageShipmentUsecase(ShipmentStore shipmentStore) {
        this.shipmentStore = shipmentStore;
    }

    @Transactional
    public void handle(StageShipmentCommand command) {
        Shipment shipment = required(command.shipmentId());
        shipment.stage(command.stagedAt());
        shipmentStore.save(shipment);
        log.info("WMS shipment staged: shipmentId={}, status={}", shipment.id(), shipment.status());
    }

    private Shipment required(java.util.UUID shipmentId) {
        return shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
