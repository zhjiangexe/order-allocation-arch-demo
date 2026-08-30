package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.event.ShipmentCancelled;
import com.flowzati.archone.wms.outbound.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 完成一張 Shipment 的停止作業與必要 recovery，並發布唯一的取消終態。 */
public class CompleteShipmentCancellationUsecase {

    private final ShipmentStore shipmentStore;
    private final ShipmentCancelledPublisher shipmentCancelledPublisher;

    public CompleteShipmentCancellationUsecase(
            ShipmentStore shipmentStore, ShipmentCancelledPublisher shipmentCancelledPublisher) {
        this.shipmentStore = shipmentStore;
        this.shipmentCancelledPublisher = shipmentCancelledPublisher;
    }

    @Transactional
    public void execute(UUID shipmentId, Instant completedAt) {
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        Objects.requireNonNull(completedAt, "Cancellation completion time is required");
        Shipment shipment = shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
        if (!shipment.completeCancellation(completedAt)) {
            return;
        }
        shipmentStore.save(shipment);
        shipmentCancelledPublisher.publish(ShipmentCancelled.from(shipment));
    }
}
