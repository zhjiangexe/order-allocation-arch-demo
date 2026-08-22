package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.wms.outbound.application.command.HandOverShipmentCommand;
import com.flowzati.archone.wms.outbound.application.event.ShipmentHandedOverPublicationFactory;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** 完成 WMS 對承運人的 custody handover；運輸離站後續由 TMS 負責。 */
public class HandOverShipmentUsecase {

    private static final Logger log = LoggerFactory.getLogger(HandOverShipmentUsecase.class);

    private final ShipmentRepository shipmentRepository;
    private final IntegrationEventPublisher integrationEventPublisher;

    public HandOverShipmentUsecase(
            ShipmentRepository shipmentRepository, IntegrationEventPublisher integrationEventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Transactional
    public void handle(HandOverShipmentCommand command) {
        Shipment shipment = shipmentRepository
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        boolean handedOver = shipment.handOverToCarrier(command.handedOverAt());
        if (!handedOver) {
            return;
        }
        shipmentRepository.save(shipment);
        integrationEventPublisher.publish(
                ShipmentHandedOverPublicationFactory.handedOver(shipment, command.handedOverAt()));
        log.info(
                "WMS shipment handed over and integration event published: shipmentId={}, orderId={}, status={}",
                shipment.id(),
                shipment.orderId(),
                shipment.status());
    }
}
