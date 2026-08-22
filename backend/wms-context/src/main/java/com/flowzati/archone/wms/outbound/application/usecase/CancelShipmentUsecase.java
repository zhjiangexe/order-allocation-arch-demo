package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationStatus;
import org.springframework.transaction.annotation.Transactional;

public class CancelShipmentUsecase {

    private final ShipmentRepository shipmentRepository;

    public CancelShipmentUsecase(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public ShipmentCancellationStatus handle(CancelShipmentCommand command) {
        Shipment shipment = shipmentRepository
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        ShipmentCancellationStatus outcome = shipment.cancel(command.requestId(), command.requestedAt());
        shipmentRepository.save(shipment);
        return outcome;
    }
}
