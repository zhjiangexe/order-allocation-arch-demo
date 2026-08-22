package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.ConfirmPickCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class ConfirmPickUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPickUsecase.class);

    private final ShipmentRepository shipmentRepository;

    public ConfirmPickUsecase(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public void handle(ConfirmPickCommand command) {
        Shipment shipment = shipmentRepository
                .findByPickTaskId(command.pickTaskId())
                .orElseThrow(
                        () -> new IllegalStateException("Shipment for pick task not found: " + command.pickTaskId()));
        shipment.confirmPick(command.pickTaskId(), command.actualQuantity(), command.confirmedAt());
        shipmentRepository.save(shipment);
        log.info(
                "WMS pick confirmed: shipmentId={}, pickTaskId={}, actualQuantity={}, status={}",
                shipment.id(),
                command.pickTaskId(),
                command.actualQuantity(),
                shipment.status());
    }
}
