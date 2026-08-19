package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.StageShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class StageShipmentUsecase {

    private final ShipmentRepository shipmentRepository;
    private final DomainEventPublisher eventPublisher;

    public StageShipmentUsecase(ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
    }

    public void handle(StageShipmentCommand command) {
        Shipment shipment = required(command.shipmentId());
        shipment.stage(command.stagedAt());
        shipmentRepository.save(shipment);
        shipment.releaseEvents().forEach(eventPublisher::publish);
    }

    private Shipment required(java.util.UUID shipmentId) {
        return shipmentRepository
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
