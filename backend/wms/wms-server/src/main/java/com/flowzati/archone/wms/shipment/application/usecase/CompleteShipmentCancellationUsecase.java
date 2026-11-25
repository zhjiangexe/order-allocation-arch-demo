package com.flowzati.archone.wms.shipment.application.usecase;

import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancelled;
import com.flowzati.archone.wms.shipment.application.invocation.CompleteShipmentCancellationCommand;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 完成一張 Shipment 的停止作業與必要 recovery，並發布唯一的取消終態。 */
@Service
public class CompleteShipmentCancellationUsecase {

    private final ShipmentStore shipmentStore;
    private final ShipmentCancelledPublisher shipmentCancelledPublisher;
    private final ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher;

    public CompleteShipmentCancellationUsecase(
            ShipmentStore shipmentStore,
            ShipmentCancelledPublisher shipmentCancelledPublisher,
            ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher) {
        this.shipmentStore = shipmentStore;
        this.shipmentCancelledPublisher = shipmentCancelledPublisher;
        this.shipmentCancellationCompletedPublisher = shipmentCancellationCompletedPublisher;
    }

    @Transactional
    public void execute(CompleteShipmentCancellationCommand command) {
        UUID shipmentId = command.shipmentId();
        Instant completedAt = command.completedAt();
        Shipment shipment = shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
        if (!shipment.completeCancellation(completedAt)) {
            return;
        }
        shipmentStore.save(shipment);
        shipmentCancellationCompletedPublisher.publish(new ShipmentCancellationCompleted(shipment.id(), completedAt));
        shipmentCancelledPublisher.publish(ShipmentCancelled.from(shipment));
    }
}
