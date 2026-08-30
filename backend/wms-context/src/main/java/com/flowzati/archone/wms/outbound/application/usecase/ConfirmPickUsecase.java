package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.ConfirmPickCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class ConfirmPickUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPickUsecase.class);

    private final ShipmentStore shipmentStore;

    public ConfirmPickUsecase(ShipmentStore shipmentStore) {
        this.shipmentStore = shipmentStore;
    }

    @Transactional
    public void handle(ConfirmPickCommand command) {
        Shipment shipment = shipmentStore
                .findByPickTaskId(command.pickTaskId())
                .orElseThrow(
                        () -> new IllegalStateException("Shipment for pick task not found: " + command.pickTaskId()));
        shipment.confirmPick(command.pickTaskId(), command.actualQuantity(), command.confirmedAt());
        shipmentStore.save(shipment);
        log.info(
                "WMS pick confirmed: shipmentId={}, pickTaskId={}, actualQuantity={}, status={}",
                shipment.id(),
                command.pickTaskId(),
                command.actualQuantity(),
                shipment.status());
    }
}
