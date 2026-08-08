package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.PackShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

public class PackShipmentUsecase {

  private final ShipmentRepository shipmentRepository;
  private final DomainEventPublisher eventPublisher;

  public PackShipmentUsecase(ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
    this.shipmentRepository = shipmentRepository;
    this.eventPublisher = eventPublisher;
  }

  public void handle(PackShipmentCommand command) {
    Shipment shipment = required(command.shipmentId());
    shipment.pack(command.packedAt());
    shipmentRepository.save(shipment);
    shipment.releaseEvents().forEach(eventPublisher::publish);
  }

  private Shipment required(java.util.UUID shipmentId) {
    return shipmentRepository.findById(shipmentId)
        .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
  }
}
