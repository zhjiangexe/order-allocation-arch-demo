package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.event.ShipmentCancelledPublicationFactory;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationState;
import org.springframework.transaction.annotation.Transactional;

public class CancelShipmentUsecase {

    private final ShipmentRepository shipmentRepository;
    private final IntegrationEventPublisher integrationEventPublisher;
    private final BusinessClock appClock;

    public CancelShipmentUsecase(
            ShipmentRepository shipmentRepository,
            IntegrationEventPublisher integrationEventPublisher,
            BusinessClock appClock) {
        this.shipmentRepository = shipmentRepository;
        this.integrationEventPublisher = integrationEventPublisher;
        this.appClock = appClock;
    }

    @Transactional
    public CancelShipmentStatus handle(CancelShipmentCommand command) {
        Shipment shipment = shipmentRepository
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        CancelShipmentStatus status =
                shipment.cancel(command.requestId(), command.requestedAt(), command.reason(), appClock.instant());
        shipmentRepository.save(shipment);
        if (status == CancelShipmentStatus.ACCEPTED
                && shipment.cancellationStateValue().orElse(null) == ShipmentCancellationState.COMPLETED) {
            integrationEventPublisher.publish(ShipmentCancelledPublicationFactory.cancelled(shipment));
        }
        return status;
    }
}
