package com.flowzati.archone.wms.picking.application.event;

import com.flowzati.archone.wms.picking.domain.type.PickingWorkStatus;
import java.time.Instant;
import java.util.UUID;

/** PickingWork 的作業狀態已改變；Shipment 只投影這個 checkpoint，不持有 PickTask。 */
public record PickingWorkStatusChanged(
        UUID pickingWorkId, UUID shipmentId, PickingWorkStatus status, Instant occurredAt) {

    public PickingWorkStatusChanged {
        if (pickingWorkId == null || shipmentId == null || status == null || occurredAt == null) {
            throw new IllegalArgumentException("PickingWorkStatusChanged requires identities, status and time");
        }
    }
}
