package com.flowzati.archone.wms.dispatch.application.event;

import com.flowzati.archone.wms.dispatch.domain.type.ShipmentDispatchStatus;
import java.time.Instant;
import java.util.UUID;

/** ShipmentDispatch checkpoint 已完成；Shipment 只保存跨流程可見的 lifecycle projection。 */
public record ShipmentDispatchStatusChanged(
        UUID shipmentDispatchId, UUID shipmentId, ShipmentDispatchStatus status, Instant occurredAt) {

    public ShipmentDispatchStatusChanged {
        if (shipmentDispatchId == null || shipmentId == null || status == null || occurredAt == null) {
            throw new IllegalArgumentException("ShipmentDispatchStatusChanged requires identities, status and time");
        }
    }
}
