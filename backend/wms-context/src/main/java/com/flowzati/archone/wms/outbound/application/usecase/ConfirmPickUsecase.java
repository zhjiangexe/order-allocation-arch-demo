package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.ConfirmPickCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class ConfirmPickUsecase {

    private final ShipmentRepository shipmentRepository;
    private final DomainEventPublisher eventPublisher;

    public ConfirmPickUsecase(ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
    }

    public void handle(ConfirmPickCommand command) {
        Shipment shipment = shipmentRepository
                .findByPickTaskId(command.pickTaskId())
                .orElseThrow(
                        () -> new IllegalStateException("Shipment for pick task not found: " + command.pickTaskId()));
        shipment.confirmPick(command.pickTaskId(), command.actualQuantity(), command.confirmedAt());
        shipmentRepository.save(shipment);
        shipment.releaseEvents().forEach(eventPublisher::publish);
    }
}
