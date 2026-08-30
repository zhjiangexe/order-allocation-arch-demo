package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.HandOverShipmentCommand;
import com.flowzati.archone.wms.outbound.application.event.ShipmentHandedOver;
import com.flowzati.archone.wms.outbound.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** 完成 WMS 對承運人的 custody handover；運輸離站後續由 TMS 負責。 */
public class HandOverShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(HandOverShipmentUsecase.class);

    private final ShipmentStore shipmentStore;
    private final ShipmentHandedOverPublisher shipmentHandedOverPublisher;

    public HandOverShipmentUsecase(
            ShipmentStore shipmentStore, ShipmentHandedOverPublisher shipmentHandedOverPublisher) {
        this.shipmentStore = shipmentStore;
        this.shipmentHandedOverPublisher = shipmentHandedOverPublisher;
    }

    @Transactional
    public void handle(HandOverShipmentCommand command) {
        Shipment shipment = shipmentStore
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        boolean handedOver = shipment.handOverToCarrier(command.handedOverAt());
        if (!handedOver) {
            return;
        }
        shipmentStore.save(shipment);
        shipmentHandedOverPublisher.publish(ShipmentHandedOver.from(shipment, command.handedOverAt()));
        log.info(
                "WMS shipment handed over and integration event published: shipmentId={}, orderId={}, status={}",
                shipment.id(),
                shipment.orderId(),
                shipment.status());
    }
}
