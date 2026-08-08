package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.model.CancellationOutcome;
import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class CancelShipmentUsecase {

  private final ShipmentRepository shipmentRepository;
  private final DomainEventPublisher eventPublisher;

  public CancelShipmentUsecase(ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
    this.shipmentRepository = shipmentRepository;
    this.eventPublisher = eventPublisher;
  }

  public CancellationOutcome handle(CancelShipmentCommand command) {
    Shipment shipment = shipmentRepository.findById(command.shipmentId())
        .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
    CancellationOutcome outcome = shipment.cancel(command.requestId(), command.requestedAt());
    shipmentRepository.save(shipment);
    shipment.releaseEvents().forEach(eventPublisher::publish);
    return outcome;
  }
}
