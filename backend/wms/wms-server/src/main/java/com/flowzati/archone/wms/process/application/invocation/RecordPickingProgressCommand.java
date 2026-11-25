package com.flowzati.archone.wms.process.application.invocation;

import com.flowzati.archone.wms.picking.domain.enums.PickingWorkStatus;
import java.time.Instant;
import java.util.UUID;

/** 一張出貨單的揀貨工作狀態改變了。 */
public record RecordPickingProgressCommand(UUID shipmentId, PickingWorkStatus status, Instant occurredAt) {

    public RecordPickingProgressCommand {
        if (shipmentId == null || status == null || occurredAt == null) {
            throw new IllegalArgumentException("Shipment, picking status and time are required");
        }
    }
}
