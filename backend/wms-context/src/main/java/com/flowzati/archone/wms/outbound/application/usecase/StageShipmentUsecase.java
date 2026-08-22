package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.StageShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class StageShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(StageShipmentUsecase.class);

    private final ShipmentRepository shipmentRepository;

    public StageShipmentUsecase(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public void handle(StageShipmentCommand command) {
        Shipment shipment = required(command.shipmentId());
        shipment.stage(command.stagedAt());
        shipmentRepository.save(shipment);
        log.info("WMS shipment staged: shipmentId={}, status={}", shipment.id(), shipment.status());
    }

    private Shipment required(java.util.UUID shipmentId) {
        return shipmentRepository
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
