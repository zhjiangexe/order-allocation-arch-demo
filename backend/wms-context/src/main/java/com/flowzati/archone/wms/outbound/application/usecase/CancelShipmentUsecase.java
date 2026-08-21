package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationStatus;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class CancelShipmentUsecase {

    private final ShipmentRepository shipmentRepository;
    private final DomainEventPublisher eventPublisher;

    public CancelShipmentUsecase(ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ShipmentCancellationStatus handle(CancelShipmentCommand command) {
        Shipment shipment = shipmentRepository
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        ShipmentCancellationStatus outcome = shipment.cancel(command.requestId(), command.requestedAt());
        shipmentRepository.save(shipment);
        shipment.releaseEvents().forEach(eventPublisher::publish);
        return outcome;
    }
}
