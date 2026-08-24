package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.wms.outbound.application.event.ShipmentCancelledPublicationFactory;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 完成一張 Shipment 的停止作業與必要 recovery，並發布唯一的取消終態。 */
public class CompleteShipmentCancellationUsecase {

    private final ShipmentRepository shipmentRepository;
    private final IntegrationEventPublisher integrationEventPublisher;

    public CompleteShipmentCancellationUsecase(
            ShipmentRepository shipmentRepository, IntegrationEventPublisher integrationEventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Transactional
    public void execute(UUID shipmentId, Instant completedAt) {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(completedAt, "Cancellation completion time is required");
        Shipment shipment = shipmentRepository
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
        if (!shipment.completeCancellation(completedAt)) {
            return;
        }
        shipmentRepository.save(shipment);
        integrationEventPublisher.publish(ShipmentCancelledPublicationFactory.cancelled(shipment));
    }
}
