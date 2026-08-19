package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.UUID;

/** 要求目前的模擬 WMS 將一張到期 Shipment 完整推進到 carrier handover。 */
public record SimulateWarehouseOperationsCommand(UUID shipmentId, Instant processedAt) {

    public SimulateWarehouseOperationsCommand {
        if (shipmentId == null || processedAt == null) {
            throw new IllegalArgumentException("Shipment simulation requires Shipment ID and processing time");
        }
    }
}
