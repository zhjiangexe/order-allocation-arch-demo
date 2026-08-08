package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.HandOverShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

/** 完成 WMS 對承運人的 custody handover；運輸離站後續由 TMS 負責。 */
public class HandOverShipmentUsecase {

  private final ShipmentRepository shipmentRepository;
  private final DomainEventPublisher eventPublisher;

  public HandOverShipmentUsecase(
      ShipmentRepository shipmentRepository,
      DomainEventPublisher eventPublisher
  ) {
    this.shipmentRepository = shipmentRepository;
    this.eventPublisher = eventPublisher;
  }

  public void handle(HandOverShipmentCommand command) {
    Shipment shipment = shipmentRepository.findById(command.shipmentId())
        .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
    shipment.handOverToCarrier(command.handedOverAt());
    shipmentRepository.save(shipment);
    shipment.releaseEvents().forEach(eventPublisher::publish);
  }
}
