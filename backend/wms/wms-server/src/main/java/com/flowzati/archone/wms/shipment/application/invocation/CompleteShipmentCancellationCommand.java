package com.flowzati.archone.wms.shipment.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** 完成一張已在取消中的 Shipment 的停止作業。 */
public record CompleteShipmentCancellationCommand(UUID shipmentId, Instant completedAt) {

    public CompleteShipmentCancellationCommand {
        if (shipmentId == null) {
            throw new IllegalArgumentException("Shipment ID is required");
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("Cancellation completion time is required");
        }
    }
}
