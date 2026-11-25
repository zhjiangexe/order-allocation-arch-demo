package com.flowzati.archone.wms.process.application.invocation;

import com.flowzati.archone.wms.dispatch.domain.enums.ShipmentDispatchStatus;
import java.time.Instant;
import java.util.UUID;

/** 一張出貨單的出庫作業（包裝、暫存、交給物流）狀態改變了。 */
public record RecordDispatchProgressCommand(UUID shipmentId, ShipmentDispatchStatus status, Instant occurredAt) {

    public RecordDispatchProgressCommand {
        if (shipmentId == null || status == null || occurredAt == null) {
            throw new IllegalArgumentException("Shipment, dispatch status and time are required");
        }
    }
}
