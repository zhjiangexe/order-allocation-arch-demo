package com.flowzati.archone.wms.shipment.application.event;

import java.time.Instant;
import java.util.UUID;

/** Shipment cancellation 已完成，Picking 可關閉尚存的作業並保留現場歷史。 */
public record ShipmentCancellationCompleted(UUID shipmentId, Instant completedAt) {

    public ShipmentCancellationCompleted {
        if (shipmentId == null || completedAt == null) {
            throw new IllegalArgumentException("ShipmentCancellationCompleted requires Shipment ID and time");
        }
    }
}
